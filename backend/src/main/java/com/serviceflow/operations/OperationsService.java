package com.serviceflow.operations;

import com.serviceflow.auth.User;
import com.serviceflow.common.ApiException;
import com.serviceflow.ticket.Ticket;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workflow evidence and delivery share the caller's transaction. No dependency on TicketService. */
@Service
public class OperationsService {
  private final JdbcTemplate db;
  private final WorkflowProperties config;
  private final Clock clock;

  public OperationsService(JdbcTemplate db, WorkflowProperties config, Clock clock) {
    this.db=db; this.config=config; this.clock=clock;
  }

  public record SlaView(long ticketId, LocalDateTime firstResponseDue, LocalDateTime resolutionDue,
      LocalDateTime firstRespondedAt, LocalDateTime escalatedAt, boolean firstResponseBreached,
      boolean resolutionBreached, boolean resolutionPaused,LocalDateTime resolutionPausedAt,long pausedSeconds,LocalDateTime asOf) {}
  public record AuditView(long id,long ticketId,Long actorId,String actorName,String action,
      String message,LocalDateTime createdAt) {}
  public record Notice(long id,long ticketId,String title,String body,boolean read,LocalDateTime createdAt) {}
  public record NoticePage(List<Notice> items,long total,long unreadTotal,int page,int size) {}
  public record ScanResult(int scanned,int escalated,int delivered) {}
  private record Sla(LocalDateTime firstResponseDue, LocalDateTime resolutionDue,
      LocalDateTime firstRespondedAt, LocalDateTime escalatedAt,LocalDateTime pausedAt,long pausedMicros) {}

  private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
  private static LocalDateTime date(ResultSet rs,String col) throws SQLException {
    return rs.getObject(col,LocalDateTime.class);
  }

  /** Lock before INSERT: an inserted ticket holds a shared FK lock on this same team row. */
  @Transactional
  public void lockTeam(long teamId) {
    db.queryForObject("SELECT id FROM teams WHERE id=? FOR UPDATE",Long.class,teamId);
  }

  @Transactional
  public void initialize(Ticket t,long actorId) {
    LocalDateTime at=now();
    db.update("INSERT INTO ticket_sla(ticket_id,first_response_due,resolution_due) VALUES(?,?,?)",
        t.id,at.plusMinutes(config.firstResponseMinutes()),at.plusMinutes(config.resolutionMinutes()));
    record(t,actorId,"CREATED","客户提交售后问题");
    if (!config.autoAssign()) return;
    // Team row is already locked by create(). Locking reads see committed capacity even when
    // a surrounding AI transaction established an older REPEATABLE READ snapshot.
    List<Long> agents=db.queryForList("SELECT id FROM users WHERE team_id=? AND role='AGENT' AND active=TRUE ORDER BY id FOR UPDATE",Long.class,t.teamId);
    Long chosen=null; int smallest=config.agentCapacity();
    for(long agent:agents) {
      int load=db.queryForList("SELECT id FROM tickets WHERE assignee_id=? AND status<>'CLOSED' FOR UPDATE",Long.class,agent).size();
      if(load<smallest) { chosen=agent;smallest=load; }
    }
    if(chosen==null) return;
    db.update("UPDATE tickets SET assignee_id=?,status='ASSIGNED',version=version+1,updated_at=? WHERE id=? AND status='OPEN'",chosen,at,t.id);
    t.assigneeId=chosen;t.status="ASSIGNED";t.version++;
    db.update("INSERT INTO ticket_events(ticket_id,actor_id,action,from_status,to_status,message) VALUES(?,?,'AUTO_ASSIGNED','OPEN','ASSIGNED','系统按团队有效负载自动分派')",t.id,actorId);
    record(t,actorId,"AUTO_ASSIGNED","系统按团队有效负载自动分派");
  }

  @Transactional
  public void firstResponse(Ticket t) {
    db.update("UPDATE ticket_sla SET first_responded_at=? WHERE ticket_id=? AND first_responded_at IS NULL",now(),t.id);
  }

  static long elapsedMicros(LocalDateTime from,LocalDateTime to){return from==null?0:Math.max(0,java.time.temporal.ChronoUnit.MICROS.between(from,to));}
  static long pausedSeconds(long settled,LocalDateTime from,LocalDateTime at){return Math.addExact(settled,elapsedMicros(from,at))/1_000_000;}

  /** Caller has already updated/locked the ticket; lock SLA second, matching the scanner. */
  @Transactional
  public void transitionSla(Ticket ticket,String oldStatus){
    LocalDateTime at=now();Sla state=sla(ticket.id,true);
    if(ticket.status.equals("WAITING_CUSTOMER")&&!oldStatus.equals("WAITING_CUSTOMER")){
      db.update("UPDATE ticket_sla SET resolution_paused_at=? WHERE ticket_id=? AND resolution_paused_at IS NULL",at,ticket.id);
    }else if(oldStatus.equals("WAITING_CUSTOMER")&&!ticket.status.equals("WAITING_CUSTOMER")&&state.pausedAt()!=null){
      long duration=elapsedMicros(state.pausedAt(),at);
      LocalDateTime due=state.resolutionDue().plus(duration,java.time.temporal.ChronoUnit.MICROS);
      db.update("UPDATE ticket_sla SET resolution_due=?,paused_micros=?,resolution_paused_at=NULL WHERE ticket_id=?",due,Math.addExact(state.pausedMicros(),duration),ticket.id);
    }
  }

  @Transactional
  public void record(Ticket t,long actorId,String action,String message) {
    append(t,actorId,action,message,true);
  }

  @Transactional
  public void recordStaff(Ticket t,long actorId,String action,String message) {
    append(t,actorId,action,message,false);
  }

  private void append(Ticket t,long actorId,String action,String message,boolean customerVisible) {
    LocalDateTime at=now();GeneratedKeyHolder key=new GeneratedKeyHolder();
    db.update(connection->{
      PreparedStatement statement=connection.prepareStatement("INSERT INTO ticket_audit(ticket_id,actor_id,action,message,created_at) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1,t.id);
      if(actorId>0)statement.setLong(2,actorId);else statement.setNull(2,Types.BIGINT);
      statement.setString(3,action);statement.setString(4,message);statement.setObject(5,at);return statement;
    },key);
    long auditId=Objects.requireNonNull(key.getKey()).longValue();
    Set<Long> recipients=new LinkedHashSet<>(db.queryForList("SELECT id FROM users WHERE active=TRUE AND (role='ADMIN' OR (role='SUPERVISOR' AND team_id=?) OR (role='AGENT' AND id=? AND team_id=?))",Long.class,t.teamId,t.assigneeId,t.teamId));
    if(customerVisible)recipients.add(t.customerId);
    String title=switch(action) {
      case "SLA_ESCALATED" -> "工单已升级处理";
      case "AUTO_ASSIGNED","ASSIGNED" -> "工单分派更新";
      case "CREATED" -> "新工单已创建";
      case "INTERNAL_NOTE" -> "工单内部记录更新";
      default -> "工单进度更新";
    };
    // Only fixed metadata enters notices. Internal notes, AI input and resolution text stay in
    // scoped ticket/audit views; caller-provided content is never copied into the outbox.
    String body="工单 "+t.reference+" 有新的处理记录，请查看工单详情。";
    for(long recipient:recipients)db.update("INSERT INTO notification_outbox(audit_id,ticket_id,user_id,title,body,created_at) VALUES(?,?,?,?,?,?)",auditId,t.id,recipient,title,body,at);
  }

  private Sla sla(long id,boolean lock) {
    return db.queryForObject("SELECT * FROM ticket_sla WHERE ticket_id=?"+(lock?" FOR UPDATE":""),
        (rs,n)->new Sla(date(rs,"first_response_due"),date(rs,"resolution_due"),date(rs,"first_responded_at"),date(rs,"escalated_at"),date(rs,"resolution_paused_at"),rs.getLong("paused_micros")),id);
  }

  @Transactional(readOnly=true)
  public SlaView view(Ticket t) {
    Sla s=sla(t.id,false);LocalDateTime at=now();boolean open=!t.status.equals("CLOSED");
    return new SlaView(t.id,s.firstResponseDue(),s.resolutionDue(),s.firstRespondedAt(),s.escalatedAt(),
        open&&s.firstRespondedAt()==null&&!s.firstResponseDue().isAfter(at),
        open&&!t.status.equals("WAITING_CUSTOMER")&&!s.resolutionDue().isAfter(at),t.status.equals("WAITING_CUSTOMER"),s.pausedAt(),pausedSeconds(s.pausedMicros(),s.pausedAt(),at),at);
  }

  @Transactional(readOnly=true)
  public List<AuditView> audit(long id) {
    return db.query("SELECT a.*,COALESCE(u.display_name,'系统') actor_name FROM ticket_audit a LEFT JOIN users u ON u.id=a.actor_id WHERE a.ticket_id=? ORDER BY a.id",
        (rs,n)->new AuditView(rs.getLong("id"),rs.getLong("ticket_id"),rs.getObject("actor_id",Long.class),rs.getString("actor_name"),rs.getString("action"),rs.getString("message"),date(rs,"created_at")),id);
  }

  private static final RowMapper<Notice> NOTICE=(rs,n)->new Notice(rs.getLong("id"),rs.getLong("ticket_id"),rs.getString("title"),rs.getString("body"),rs.getBoolean("is_read"),date(rs,"created_at"));

  @Transactional(readOnly=true)
  public List<Notice> notifications(User u) {
    return db.query("SELECT * FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 100",NOTICE,u.id);
  }

  @Transactional(readOnly=true)
  public NoticePage notificationInbox(User u,int page,int size,boolean unreadOnly) {
    if(page<1 || size<1 || size>100)throw ApiException.bad("页码必须大于0，每页数量须为1至100");
    long unread=db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND is_read=FALSE",Long.class,u.id);
    long total=unreadOnly?unread:db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=?",Long.class,u.id);
    String filter=unreadOnly?" AND is_read=FALSE":"";
    List<Notice> items=db.query("SELECT * FROM notifications WHERE user_id=?"+filter+" ORDER BY id DESC LIMIT ? OFFSET ?",NOTICE,u.id,size,((long)page-1)*size);
    return new NoticePage(items,total,unread,page,size);
  }

  @Transactional
  public int readAll(User u) {
    return db.update("UPDATE notifications SET is_read=TRUE WHERE user_id=? AND is_read=FALSE",u.id);
  }

  @Transactional
  public Notice read(User u,long id) {
    List<Notice> found=db.query("SELECT * FROM notifications WHERE id=? AND user_id=? FOR UPDATE",NOTICE,id,u.id);
    if(found.isEmpty())throw ApiException.missing();
    db.update("UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?",id,u.id);
    Notice n=found.getFirst();return new Notice(n.id(),n.ticketId(),n.title(),n.body(),true,n.createdAt());
  }

  private Ticket lockedTicket(long id) {
    List<Ticket> list=db.query("SELECT * FROM tickets WHERE id=? FOR UPDATE",(rs,n)->{
      Ticket t=new Ticket();t.id=rs.getLong("id");t.reference=rs.getString("reference");t.customerId=rs.getLong("customer_id");t.teamId=rs.getLong("team_id");t.assigneeId=rs.getObject("assignee_id",Long.class);t.status=rs.getString("status");t.priority=rs.getString("priority");return t;
    },id);return list.isEmpty()?null:list.getFirst();
  }

  /** Supervisor scans only its team; the scheduled worker/admin passes null for all teams. */
  @Transactional
  public ScanResult scan(Long teamId) {
    LocalDateTime at=now();
    List<Long> ids=db.queryForList("SELECT t.id FROM tickets t JOIN ticket_sla s ON s.ticket_id=t.id WHERE t.status<>'CLOSED' AND s.escalated_at IS NULL AND (? IS NULL OR t.team_id=?) AND ((s.first_responded_at IS NULL AND s.first_response_due<=?) OR (t.status<>'WAITING_CUSTOMER' AND s.resolution_due<=?)) ORDER BY LEAST(s.first_response_due,s.resolution_due),t.id LIMIT ?",Long.class,teamId,teamId,at,at,config.scanBatchSize());
    int escalated=0;
    for(long id:ids) {
      Ticket t=lockedTicket(id);if(t==null||t.status.equals("CLOSED"))continue;
      Sla s=sla(id,true);
      if(s.escalatedAt()!=null||!((s.firstRespondedAt()==null&&!s.firstResponseDue().isAfter(at))||(!t.status.equals("WAITING_CUSTOMER")&&!s.resolutionDue().isAfter(at))))continue;
      if(db.update("UPDATE ticket_sla SET escalated_at=? WHERE ticket_id=? AND escalated_at IS NULL",at,id)!=1)continue;
      db.update("UPDATE tickets SET priority=CASE priority WHEN 'LOW' THEN 'NORMAL' WHEN 'NORMAL' THEN 'HIGH' ELSE 'URGENT' END,version=version+1,updated_at=? WHERE id=?",at,id);
      record(t,0,"SLA_ESCALATED","服务时限已超时，系统已提升优先级并通知负责人");escalated++;
    }
    return new ScanResult(ids.size(),escalated,deliver(teamId));
  }

  private int deliver(Long teamId) {
    List<Long> pending=db.queryForList("SELECT o.id FROM notification_outbox o JOIN tickets t ON t.id=o.ticket_id WHERE o.delivered_at IS NULL AND (? IS NULL OR t.team_id=?) ORDER BY o.id LIMIT ? FOR UPDATE SKIP LOCKED",Long.class,teamId,teamId,config.scanBatchSize());
    int delivered=0;
    for(long id:pending) {
      db.update("INSERT INTO notifications(outbox_id,ticket_id,user_id,title,body,created_at) SELECT id,ticket_id,user_id,title,body,created_at FROM notification_outbox WHERE id=? ON DUPLICATE KEY UPDATE outbox_id=VALUES(outbox_id)",id);
      delivered+=db.update("UPDATE notification_outbox SET delivered_at=? WHERE id=? AND delivered_at IS NULL",now(),id);
    }
    return delivered;
  }
}
