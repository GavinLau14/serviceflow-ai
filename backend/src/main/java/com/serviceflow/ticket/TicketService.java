package com.serviceflow.ticket;

import static com.serviceflow.ticket.TicketRequests.*;

import com.serviceflow.auth.*;
import com.serviceflow.common.ApiException;
import com.serviceflow.operations.OperationsService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
  private final TicketMapper tickets;
  private final UserMapper users;
  private final OperationsService operations;

  public TicketService(TicketMapper tickets, UserMapper users, OperationsService operations) {
    this.tickets = tickets;
    this.users = users;
    this.operations = operations;
  }

  public record Page(List<Ticket> items, long total, int page, int size) {}

  public record Detail(
      Ticket ticket,
      List<TicketMapper.CommentView> comments,
      List<TicketMapper.EventView> events,
      List<String> allowedActions) {}

  @Transactional(readOnly = true)
  public Page list(User u, Status status, String q, int page, int size) {
    if (page < 1 || page > 100000 || size < 1 || size > 100 || q != null && q.length() > 100)
      throw ApiException.bad("分页或搜索参数无效");
    String search = q == null || q.isBlank() ? null : q.strip();
    String state = status == null ? null : status.name();
    return new Page(
        tickets.list(u, state, search, size, (page - 1) * size),
        tickets.count(u, state, search),
        page,
        size);
  }

  public Ticket require(User u, long id) {
    Ticket t = tickets.find(id, u);
    if (t == null) throw ApiException.missing();
    return t;
  }

  @Transactional(readOnly = true)
  public Detail detail(User u, long id) {
    Ticket t = require(u, id);
    return new Detail(t, tickets.comments(id, u.is("CUSTOMER")), tickets.events(id), actions(u, t));
  }

  public List<String> actions(User u, Ticket t) {
    List<String> out = new ArrayList<>();
    if (t.status.equals("CLOSED")) return out;
    out.add("COMMENT");
    if (!u.is("CUSTOMER")) out.add("INTERNAL_NOTE");
    if (u.is("ADMIN") || u.is("SUPERVISOR")) out.add("ASSIGN");
    if (Objects.equals(t.assigneeId, u.id) && !u.is("CUSTOMER")) {
      if (t.status.equals("ASSIGNED")) out.add("START");
      if (t.status.equals("IN_PROGRESS")) out.add("RESOLVE");
    }
    if (u.is("CUSTOMER") && t.customerId == u.id && t.status.equals("WAITING_CUSTOMER")) {
      out.add("CONFIRM");
      out.add("REJECT");
    }
    return out;
  }

  @Transactional
  public Ticket create(User u, Create req) {
    if (!u.is("CUSTOMER")) throw ApiException.forbidden();
    Ticket t = new Ticket();
    t.reference =
        "SF-"
            + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16)
                .toUpperCase(Locale.ROOT);
    t.title = req.title().strip();
    t.description = req.description().strip();
    t.category = req.category().name();
    t.priority = req.priority().name();
    t.status = "OPEN";
    t.customerId = u.id;
    t.teamId = req.category() == Category.DEVICE ? 1 : 2;
    operations.lockTeam(t.teamId);
    tickets.insert(t);
    tickets.event(t.id, u.id, "CREATED", null, "OPEN", "客户提交售后问题");
    operations.initialize(t, u.id);
    return require(u, t.id);
  }

  @Transactional
  public Ticket assign(User u, long id, Assign req) {
    Ticket t = require(u, id);
    if (!u.is("ADMIN") && !u.is("SUPERVISOR")) throw ApiException.forbidden();
    if (!req.confirmed()) throw ApiException.bad("请显式确认分派操作");
    if (t.status.equals("CLOSED")) throw new ApiException(409, "INVALID_TRANSITION", "已关闭工单不可分派");
    User agent = users.byId(req.assigneeId());
    if (agent == null || !agent.is("AGENT") || !Objects.equals(agent.teamId, t.teamId))
      throw ApiException.bad("请选择工单所属团队的在职客服");
    checkVersion(t, req.version());
    operations.lockTeam(t.teamId);
    String old = t.status;
    t.assigneeId = agent.id;
    if (t.status.equals("OPEN")) t.status = "ASSIGNED";
    persist(t);
    tickets.event(id, u.id, "ASSIGNED", old, t.status, "已分派给 " + agent.displayName);
    operations.record(t, u.id, "ASSIGNED", "工单已重新分派");
    return require(u, id);
  }

  @Transactional
  public Ticket transition(User u, long id, Transition req) {
    Ticket t = require(u, id);
    String target = req.target().name();
    String old = t.status;
    if (target.equals("CLOSED") || target.equals("IN_PROGRESS") && old.equals("WAITING_CUSTOMER")) {
      if (!u.is("CUSTOMER") || t.customerId != u.id) throw ApiException.forbidden();
      if (!req.confirmed()) throw ApiException.bad("请客户本人显式确认");
    } else if (u.is("CUSTOMER") || !Objects.equals(t.assigneeId, u.id))
      throw ApiException.forbidden();
    boolean valid =
        old.equals("ASSIGNED") && target.equals("IN_PROGRESS")
            || old.equals("IN_PROGRESS") && target.equals("WAITING_CUSTOMER")
            || old.equals("WAITING_CUSTOMER")
                && (target.equals("CLOSED") || target.equals("IN_PROGRESS"));
    if (!valid) throw new ApiException(409, "INVALID_TRANSITION", "当前状态不允许此操作");
    checkVersion(t, req.version());
    t.status = target;
    persist(t);
    tickets.event(
        id,
        u.id,
        target.equals("CLOSED")
            ? "CUSTOMER_CONFIRMED"
            : old.equals("WAITING_CUSTOMER") ? "CUSTOMER_REJECTED" : "STATUS_CHANGED",
        old,
        target,
        req.message().strip());
    operations.transitionSla(t, old);
    if (target.equals("WAITING_CUSTOMER")) operations.firstResponse(t);
    operations.record(t, u.id, target.equals("CLOSED") ? "CUSTOMER_CONFIRMED" :
        old.equals("WAITING_CUSTOMER") ? "CUSTOMER_REJECTED" : "STATUS_CHANGED", req.message().strip());
    return require(u, id);
  }

  @Transactional
  public Ticket comment(User u, long id, Comment req) {
    Ticket t = require(u, id);
    if (req.internal() && u.is("CUSTOMER")) throw ApiException.forbidden();
    if (t.status.equals("CLOSED")) throw new ApiException(409, "TICKET_CLOSED", "已关闭工单不可回复");
    checkVersion(t, req.version());
    persist(t);
    tickets.comment(id, u.id, req.body().strip(), req.internal());
    if (req.internal()) {
      operations.recordStaff(t, u.id, "INTERNAL_NOTE", "客服添加了内部记录");
    } else {
      if (!u.is("CUSTOMER")) operations.firstResponse(t);
      operations.record(t, u.id, "COMMENTED", "工单新增公开回复");
    }
    return require(u, id);
  }

  private void checkVersion(Ticket t, int v) {
    if (t.version != v) throw ApiException.conflict();
  }

  private void persist(Ticket t) {
    if (tickets.update(t) != 1) throw ApiException.conflict();
  }
}
