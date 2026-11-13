package com.serviceflow.operations;

import com.serviceflow.auth.User;
import com.serviceflow.common.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Operational deadline queue. All facts share a scoped DB snapshot and one UTC instant. */
@Service
public class SlaInboxService {
  public enum Filter { BREACHED,DUE_SOON,WAITING_CUSTOMER,ALL }
  public record Summary(long active,long breached,long dueSoon,long waitingCustomer,long unassigned) {}
  public record Item(long id,String reference,String title,String status,String priority,long teamId,Long assigneeId,
      String assigneeName,int version,LocalDateTime nextDue,boolean firstResponseBreached,boolean resolutionBreached,
      boolean resolutionPaused,long pausedSeconds,LocalDateTime escalatedAt) {}
  public record Page(List<Item> items,long total,int page,int size,LocalDateTime asOf,int dueSoonMinutes,Summary summary) {}
  private final JdbcTemplate db;private final Clock clock;
  public SlaInboxService(JdbcTemplate db,Clock clock){this.db=db;this.clock=clock;}

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page page(User user,int page,int size,Filter filter,String query){
    String scope;List<Object> scopeArgs=new ArrayList<>();
    switch(user.role){
      case "ADMIN" -> scope="1=1";
      case "SUPERVISOR" -> {if(user.teamId==null)throw ApiException.forbidden();scope="t.team_id=?";scopeArgs.add(user.teamId);}
      case "AGENT" -> {scope="t.assignee_id=?";scopeArgs.add(user.id);}
      default -> throw ApiException.forbidden();
    }
    if(page<1||page>100000||size<1||size>100||query!=null&&query.length()>100)throw ApiException.bad("分页或检索参数无效");
    String q=query==null?"":query.strip();
    LocalDateTime at=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS),soon=at.plusMinutes(30);
    String base="SELECT t.id,t.reference,t.title,t.status,t.priority,t.team_id,t.assignee_id,t.version,u.display_name assignee_name,s.first_response_due,s.resolution_due,s.first_responded_at,s.resolution_paused_at,s.paused_micros,s.escalated_at,"+
        "CASE WHEN s.first_responded_at IS NULL THEN CASE WHEN t.status='WAITING_CUSTOMER' OR s.first_response_due<=s.resolution_due THEN s.first_response_due ELSE s.resolution_due END WHEN t.status='WAITING_CUSTOMER' THEN NULL ELSE s.resolution_due END next_due "+
        "FROM tickets t JOIN ticket_sla s ON s.ticket_id=t.id LEFT JOIN users u ON u.id=t.assignee_id WHERE t.status<>'CLOSED' AND "+scope;
    List<Object> stats=new ArrayList<>(List.of(at,at,soon));stats.addAll(scopeArgs);
    Summary summary=db.queryForObject("SELECT COUNT(*) active,COALESCE(SUM(next_due<=?),0) breached,COALESCE(SUM(next_due>? AND next_due<=?),0) due_soon,COALESCE(SUM(status='WAITING_CUSTOMER'),0) waiting,COALESCE(SUM(assignee_id IS NULL),0) unassigned FROM ("+base+") b",
        (r,n)->new Summary(r.getLong("active"),r.getLong("breached"),r.getLong("due_soon"),r.getLong("waiting"),r.getLong("unassigned")),stats.toArray());
    List<Object> args=new ArrayList<>(scopeArgs);String condition;
    switch(filter){
      case BREACHED -> {condition="next_due<=?";args.add(at);}
      case DUE_SOON -> {condition="next_due>? AND next_due<=?";args.add(at);args.add(soon);}
      case WAITING_CUSTOMER -> condition="status='WAITING_CUSTOMER'";
      default -> condition="1=1";
    }
    if(!q.isEmpty()){condition+=" AND (LOCATE(?,title)>0 OR LOCATE(?,reference)>0)";args.add(q);args.add(q);}
    String from=" FROM ("+base+") b WHERE "+condition;
    long total=db.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
    args.add(size);args.add((page-1)*size);
    var items=db.query("SELECT *"+from+" ORDER BY next_due IS NULL,next_due,id LIMIT ? OFFSET ?",(r,n)->{
      LocalDateTime response=r.getObject("first_response_due",LocalDateTime.class),resolution=r.getObject("resolution_due",LocalDateTime.class),responded=r.getObject("first_responded_at",LocalDateTime.class),paused=r.getObject("resolution_paused_at",LocalDateTime.class);
      String status=r.getString("status");
      return new Item(r.getLong("id"),r.getString("reference"),r.getString("title"),status,r.getString("priority"),r.getLong("team_id"),r.getObject("assignee_id",Long.class),r.getString("assignee_name"),r.getInt("version"),r.getObject("next_due",LocalDateTime.class),responded==null&&!response.isAfter(at),!status.equals("WAITING_CUSTOMER")&&!resolution.isAfter(at),status.equals("WAITING_CUSTOMER"),OperationsService.pausedSeconds(r.getLong("paused_micros"),paused,at),r.getObject("escalated_at",LocalDateTime.class));
    },args.toArray());
    return new Page(items,total,page,size,at,30,summary);
  }
}
