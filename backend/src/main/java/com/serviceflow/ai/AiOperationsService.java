package com.serviceflow.ai;

import com.fasterxml.jackson.databind.*;
import com.serviceflow.auth.User;
import com.serviceflow.common.ApiException;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Allow-listed historical metadata only. Never expose customer prompts or model payloads. */
@Service
public class AiOperationsService {
  public record Item(String id,String mode,Long latencyMs,Integer citationCount,boolean storedProposal,
      Long ticketId,boolean handoffRequested,LocalDateTime createdAt) {}
  public record Summary(long total,long rag,long retrievalOnly,long disabled,long error,long unknown,
      long linkedTickets,long handoffRequests,long withProposal,Long averageLatencyMs) {}
  public record Page(List<Item> items,long total,int page,int size,int hours,LocalDateTime asOf,Summary summary) {}
  public record Health(String status,String mode,Boolean retrievalReady,Boolean llmConfigured,LocalDateTime checkedAt) {}
  private static final Set<String> MODES=Set.of("rag","retrieval_only","disabled","error","unknown");
  private final JdbcTemplate db;private final AiGateway gateway;private final ObjectMapper json;private final Clock clock;
  public AiOperationsService(JdbcTemplate db,AiGateway gateway,ObjectMapper json,Clock clock){this.db=db;this.gateway=gateway;this.json=json;this.clock=clock;}
  private void admin(User u){if(!u.is("ADMIN"))throw ApiException.forbidden();}
  private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page page(User user,int hours,int page,int size,String mode,boolean linkedOnly){
    admin(user);String filter=mode==null?"":mode.strip();
    if(hours<1||hours>168||page<1||page>100000||size<1||size>100||!filter.isEmpty()&&!MODES.contains(filter))throw ApiException.bad("统计窗口、分页或模式参数无效");
    LocalDateTime at=now();List<Object> bounds=List.of(at.minusHours(hours),at);
    String safe="SELECT id,latency_ms,ticket_id,handoff_requested,created_at,IF(JSON_VALID(response_json),response_json,'{}') safe_json FROM ai_runs WHERE created_at>=? AND created_at<=?";
    String value="JSON_UNQUOTE(JSON_EXTRACT(safe_json,'$.mode'))";
    String base="SELECT id,ticket_id,handoff_requested,created_at,CASE WHEN latency_ms>=0 THEN latency_ms ELSE NULL END recorded_latency,"+
        "CASE CAST("+value+" AS BINARY) WHEN 'rag' THEN 'rag' WHEN 'retrieval_only' THEN 'retrieval_only' WHEN 'disabled' THEN 'disabled' WHEN 'error' THEN 'error' ELSE 'unknown' END run_mode,"+
        "CASE WHEN JSON_TYPE(JSON_EXTRACT(safe_json,'$.citations'))='ARRAY' AND JSON_LENGTH(JSON_EXTRACT(safe_json,'$.citations'))<=10 THEN JSON_LENGTH(JSON_EXTRACT(safe_json,'$.citations')) ELSE NULL END citation_count,"+
        "CASE WHEN JSON_TYPE(JSON_EXTRACT(safe_json,'$.proposal'))='OBJECT' THEN TRUE ELSE FALSE END stored_proposal FROM ("+safe+") parsed";
    Summary summary=db.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(run_mode='rag'),0) rag_count,COALESCE(SUM(run_mode='retrieval_only'),0) retrieval_count,COALESCE(SUM(run_mode='disabled'),0) disabled_count,COALESCE(SUM(run_mode='error'),0) error_count,COALESCE(SUM(run_mode='unknown'),0) unknown_count,COALESCE(SUM(ticket_id IS NOT NULL),0) linked_count,COALESCE(SUM(handoff_requested),0) handoff_count,COALESCE(SUM(stored_proposal),0) proposal_count,AVG(recorded_latency) average_latency FROM ("+base+") records",
        (r,n)->{var average=r.getBigDecimal("average_latency");return new Summary(r.getLong("total"),r.getLong("rag_count"),r.getLong("retrieval_count"),r.getLong("disabled_count"),r.getLong("error_count"),r.getLong("unknown_count"),r.getLong("linked_count"),r.getLong("handoff_count"),r.getLong("proposal_count"),average==null?null:average.setScale(0,RoundingMode.HALF_UP).longValueExact());},bounds.toArray());
    List<Object> args=new ArrayList<>(bounds);String where=" WHERE 1=1";
    if(!filter.isEmpty()){where+=" AND run_mode=?";args.add(filter);}if(linkedOnly)where+=" AND ticket_id IS NOT NULL";
    String from=" FROM ("+base+") records"+where;
    long total=db.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
    List<Item> items=db.query("SELECT id,run_mode,recorded_latency,citation_count,stored_proposal,ticket_id,handoff_requested,created_at"+from+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
        (r,n)->new Item(r.getString("id"),r.getString("run_mode"),r.getObject("recorded_latency",Long.class),r.getObject("citation_count",Integer.class),r.getBoolean("stored_proposal"),r.getObject("ticket_id",Long.class),r.getBoolean("handoff_requested"),r.getObject("created_at",LocalDateTime.class)),args.toArray());
    return new Page(items,total,page,size,hours,at,summary);
  }

  public Health health(User user){
    admin(user);if(!gateway.enabled())return new Health("NOT_CONFIGURED","disabled",null,null,now());
    try{
      JsonNode result=json.readTree(gateway.health());String mode=result.path("mode").asText();
      if(!Set.of("rag","retrieval_only","disabled").contains(mode)||!result.path("retrieval_ready").isBoolean()||!result.path("llm_configured").isBoolean())throw new IllegalArgumentException("Invalid health contract");
      boolean retrieval=result.get("retrieval_ready").asBoolean(),llm=result.get("llm_configured").asBoolean();
      if(mode.equals("rag")&&(!retrieval||!llm)||mode.equals("retrieval_only")&&(!retrieval||llm))throw new IllegalArgumentException("Inconsistent health contract");
      return new Health("REACHABLE",mode,retrieval,llm,now());
    }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return new Health("UNAVAILABLE",null,null,null,now());}
  }
}
