package com.serviceflow.collaboration;

import com.serviceflow.auth.User;
import com.serviceflow.common.ApiException;
import com.serviceflow.operations.OperationsService;
import com.serviceflow.ticket.*;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class KnowledgeService {
  private final KnowledgeMapper knowledge;
  private final TicketService tickets;
  private final OperationsService operations;

  public KnowledgeService(KnowledgeMapper knowledge, TicketService tickets, OperationsService operations) {
    this.knowledge = knowledge;
    this.tickets = tickets;
    this.operations = operations;
  }

  private void staff(User u) {
    if (!u.is("AGENT") && !u.is("SUPERVISOR") && !u.is("ADMIN")) throw ApiException.forbidden();
  }

  @Transactional(readOnly=true)
  public List<Knowledge> list(User u) {
    staff(u);
    return knowledge.list(u);
  }

  public record Page(List<Knowledge> items,long total,long pendingTotal,int page,int size) {}

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page inbox(User u,int page,int size,String status,String query) {
    staff(u);
    String state=status==null?"":status.strip(),q=query==null?"":query.strip();
    if(page<1 || size<1 || size>100)throw ApiException.bad("页码必须大于0，每页数量须为1至100");
    if(!Set.of("","DRAFT","PUBLISHED","REJECTED").contains(state))throw ApiException.bad("知识状态无效");
    if(q.length()>100)throw ApiException.bad("检索关键词最多100个字符");
    long pending=knowledge.pending(u),total=knowledge.count(u,state,q);
    return new Page(knowledge.page(u,state,q,size,((long)page-1)*size),total,pending,page,size);
  }

  @Transactional
  public Knowledge draft(User u,long ticketId,KnowledgeController.Draft request) {
    staff(u);
    tickets.require(u,ticketId);
    Ticket ticket = knowledge.lockTicket(ticketId);
    if(ticket==null||!(u.is("ADMIN")||u.is("SUPERVISOR")&&java.util.Objects.equals(u.teamId,ticket.teamId)||u.is("AGENT")&&java.util.Objects.equals(ticket.assigneeId,u.id)))throw ApiException.missing();
    if (!request.confirmed()) throw ApiException.bad("请显式确认已脱敏的知识草稿");
    if (!"CLOSED".equals(ticket.status)) throw new ApiException(409,"TICKET_NOT_CLOSED","客户确认关闭后才可提交知识草稿");
    Knowledge existing = knowledge.draft(ticketId);
    if (existing != null) return existing;
    Knowledge row = new Knowledge();
    row.ticketId=ticket.id;
    row.teamId=ticket.teamId;
    row.authorId=u.id;
    row.title=request.title().strip();
    row.content=request.content().strip();
    knowledge.insert(row);
    knowledge.event(row.id,u.id,"DRAFT_CREATED");
    operations.recordStaff(ticket,u.id,"KNOWLEDGE_DRAFT_CREATED","已提交人工整理并确认脱敏的知识草稿，等待审核");
    return knowledge.byId(row.id);
  }

  @Transactional
  public Knowledge review(User u,long id,KnowledgeController.Review request) {
    if (!u.is("SUPERVISOR") && !u.is("ADMIN")) throw ApiException.forbidden();
    Knowledge visible=knowledge.find(id,u);
    if (visible==null) throw ApiException.missing();
    knowledge.lockTicket(visible.ticketId);
    Knowledge row=knowledge.lock(id,u);
    if (row==null) throw ApiException.missing();
    if (!request.confirmed()) throw ApiException.bad("请显式确认知识审核结果");
    String target=request.approved()?"PUBLISHED":"REJECTED";
    String note=request.note()==null||request.note().isBlank()?null:request.note().strip();
    if (!"DRAFT".equals(row.status)) {
      if (target.equals(row.status) && Objects.equals(note,row.reviewNote)) return row;
      throw new ApiException(409,"KNOWLEDGE_ALREADY_REVIEWED","知识条目已经审核，不能覆盖结论或意见，请创建新的草稿");
    }
    if(!request.approved() && note==null)throw ApiException.bad("请填写退回理由，便于提交人修正");
    if (knowledge.review(id,target,u.id,note)!=1) throw ApiException.conflict();
    knowledge.event(id,u.id,target);
    operations.recordStaff(tickets.require(u,row.ticketId),u.id,"KNOWLEDGE_"+target,"知识条目已完成显式人工审核，未自动加入 AI 检索库");
    return knowledge.byId(id);
  }
}
