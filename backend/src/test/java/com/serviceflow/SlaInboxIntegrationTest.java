package com.serviceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class SlaInboxIntegrationTest {
  static class MutableClock extends Clock {
    final AtomicReference<Instant> current=new AtomicReference<>();
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
    public Instant instant(){return current.get();}
    void at(String at){current.set(Instant.parse(at));}
  }
  @TestConfiguration static class Config {@Bean @Primary MutableClock testClock(){return new MutableClock();}}
  @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired MutableClock clock;
  @Autowired com.serviceflow.ticket.TicketService tickets;@Autowired com.serviceflow.auth.UserMapper users;@Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @BeforeEach void time(){clock.at("2030-01-01T00:00:00Z");}
  JsonNode node(ResultActions r)throws Exception{return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
  String token(String name)throws Exception{return node(mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password","Test-Only-Password-123!")))).andExpect(status().isOk())).get("token").asText();}
  ResultActions getAs(String user,String path)throws Exception{return mvc.perform(get(path).header("Authorization","Bearer "+token(user)));}
  ResultActions postAs(String user,String path,Object body)throws Exception{return mvc.perform(post(path).header("Authorization","Bearer "+token(user)).contentType("application/json").content(json.writeValueAsString(body)));}
  JsonNode move(String user,JsonNode ticket,String target)throws Exception{return node(postAs(user,"/api/tickets/"+ticket.get("id").asLong()+"/transition",Map.of("target",target,"version",ticket.get("version").asInt(),"message","演示处理记录","confirmed",true)).andExpect(status().isOk()));}
  JsonNode processing()throws Exception{
    JsonNode t=node(postAs("alice","/api/tickets",Map.of("title","暂停测试","description","虚构测试，不构成服务政策","category","DEVICE","priority","NORMAL")).andExpect(status().isCreated()));
    t=node(postAs("supervisor","/api/tickets/"+t.get("id").asLong()+"/assign",Map.of("assigneeId",3,"version",t.get("version").asInt(),"confirmed",true)).andExpect(status().isOk()));return move("agent",t,"IN_PROGRESS");
  }
  JsonNode sla(JsonNode t)throws Exception{return node(getAs("agent","/api/tickets/"+t.get("id").asLong()+"/sla").andExpect(status().isOk()));}

  @Test void resolutionDeadlineAccumulatesMultipleCustomerWaitsAndDoesNotPauseFirstResponse()throws Exception{
    JsonNode t=processing();long id=t.get("id").asLong();
    clock.at("2030-01-01T00:10:00Z");t=move("agent",t,"WAITING_CUSTOMER");
    assertThat(sla(t).get("resolutionPausedAt").asText()).isEqualTo("2030-01-01T00:10:00");
    clock.at("2030-01-01T02:10:00Z");assertThat(sla(t).get("pausedSeconds").asLong()).isEqualTo(7200);
    JsonNode old=t;t=move("alice",t,"IN_PROGRESS");
    assertThat(sla(t).get("resolutionDue").asText()).isEqualTo("2030-01-02T02:00:00");assertThat(sla(t).get("resolutionPausedAt").isNull()).isTrue();
    postAs("alice","/api/tickets/"+id+"/transition",Map.of("target","IN_PROGRESS","version",old.get("version").asInt(),"message","过期重试","confirmed",true)).andExpect(status().isForbidden());
    clock.at("2030-01-01T03:00:00Z");t=move("agent",t,"WAITING_CUSTOMER");
    postAs("alice","/api/tickets/"+id+"/transition",Map.of("target","IN_PROGRESS","version",old.get("version").asInt(),"message","过期重试","confirmed",true)).andExpect(status().isConflict());
    clock.at("2030-01-01T03:30:00Z");t=move("alice",t,"CLOSED");
    var s=sla(t);assertThat(s.get("resolutionDue").asText()).isEqualTo("2030-01-02T02:30:00");assertThat(s.get("pausedSeconds").asLong()).isEqualTo(9000);assertThat(s.get("resolutionPaused").asBoolean()).isFalse();assertThat(s.get("firstResponseDue").asText()).isEqualTo("2030-01-01T01:00:00");
  }

  @Test void waitingDoesNotEraseExistingBreachAndClockRollbackCannotSubtractTime()throws Exception{
    JsonNode t=processing();long id=t.get("id").asLong();
    clock.at("2030-01-02T01:00:00Z");t=move("agent",t,"WAITING_CUSTOMER");clock.at("2030-01-02T03:00:00Z");t=move("alice",t,"IN_PROGRESS");
    var s=sla(t);assertThat(s.get("resolutionDue").asText()).isEqualTo("2030-01-02T02:00:00");assertThat(s.get("resolutionBreached").asBoolean()).isTrue();
    t=move("agent",t,"WAITING_CUSTOMER");clock.at("2030-01-02T02:59:00Z");t=move("alice",t,"IN_PROGRESS");
    assertThat(sla(t).get("pausedSeconds").asLong()).isEqualTo(7200);assertThat(db.queryForObject("SELECT paused_micros FROM ticket_sla WHERE ticket_id=?",Long.class,id)).isEqualTo(7200000000L);
  }

  @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void failedOuterTransactionRollsBackPauseCompensationAndVersionTogether()throws Exception{
    JsonNode t=processing();long id=t.get("id").asLong();
    try{
      t=move("agent",t,"WAITING_CUSTOMER");int version=t.get("version").asInt();clock.at("2030-01-01T01:00:00Z");
      var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
      assertThatThrownBy(()->tx.executeWithoutResult(status->{tickets.transition(users.byId(1),id,new com.serviceflow.ticket.TicketRequests.Transition(com.serviceflow.ticket.TicketRequests.Status.IN_PROGRESS,version,true,"客户拒绝解决方案"));throw new IllegalStateException("fixture rollback");})).isInstanceOf(IllegalStateException.class);
      var s=sla(t);assertThat(s.get("resolutionDue").asText()).isEqualTo("2030-01-02T00:00:00");assertThat(s.get("resolutionPausedAt").isNull()).isFalse();
      assertThat(db.queryForObject("SELECT paused_micros FROM ticket_sla WHERE ticket_id=?",Long.class,id)).isZero();assertThat(db.queryForObject("SELECT version FROM tickets WHERE id=?",Integer.class,id)).isEqualTo(version);
      t=move("alice",t,"IN_PROGRESS");assertThat(sla(t).get("resolutionDue").asText()).isEqualTo("2030-01-02T01:00:00");
    }finally{db.update("DELETE FROM ticket_comments WHERE ticket_id=?",id);db.update("DELETE FROM ticket_events WHERE ticket_id=?",id);db.update("DELETE FROM tickets WHERE id=?",id);}
  }

  @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void concurrentCustomerRejectionSettlesPauseOnlyOnce()throws Exception{
    JsonNode t=processing();long id=t.get("id").asLong();
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
      t=move("agent",t,"WAITING_CUSTOMER");int version=t.get("version").asInt();clock.at("2030-01-01T01:00:00Z");
      var gate=new java.util.concurrent.CountDownLatch(1);String auth=token("alice");String body=json.writeValueAsString(Map.of("target","IN_PROGRESS","version",version,"confirmed",true,"message","客户再次请求处理"));
      java.util.concurrent.Callable<Integer> reject=()->{gate.await();return mvc.perform(post("/api/tickets/"+id+"/transition").header("Authorization","Bearer "+auth).contentType("application/json").content(body)).andReturn().getResponse().getStatus();};
      var a=pool.submit(reject);var b=pool.submit(reject);gate.countDown();var codes=List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS));assertThat(codes.stream().filter(code->code==200).count()).isEqualTo(1);assertThat(codes).allMatch(code->code==200||code==403||code==409);
      assertThat(db.queryForObject("SELECT paused_micros FROM ticket_sla WHERE ticket_id=?",Long.class,id)).isEqualTo(3600000000L);assertThat(sla(t).get("resolutionDue").asText()).isEqualTo("2030-01-02T01:00:00");assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_audit WHERE ticket_id=? AND action='CUSTOMER_REJECTED'",Long.class,id)).isEqualTo(1);
    }finally{db.update("DELETE FROM ticket_comments WHERE ticket_id=?",id);db.update("DELETE FROM ticket_events WHERE ticket_id=?",id);db.update("DELETE FROM tickets WHERE id=?",id);}
  }

  long fixture(String title,int team,Long agent,String status,String response,String resolution,boolean responded){
    var key=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement("INSERT INTO tickets(reference,title,description,category,priority,status,customer_id,team_id,assignee_id) VALUES(?,?,'内部客户细节','DEVICE','NORMAL',?,1,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);p.setString(1,"RISK-"+UUID.randomUUID().toString().substring(0,8));p.setString(2,title);p.setString(3,status);p.setInt(4,team);if(agent==null)p.setNull(5,java.sql.Types.BIGINT);else p.setLong(5,agent);return p;},key);long id=key.getKey().longValue();
    db.update("INSERT INTO ticket_sla(ticket_id,first_response_due,resolution_due,first_responded_at) VALUES(?,?,?,?)",id,response,resolution,responded?"2029-12-31 23:00:00":null);return id;
  }

  @Test void riskQueueEnforcesStaffScopeAndLiteralSearchWithoutLeakingInternalDescription()throws Exception{
    String mark="scope-"+UUID.randomUUID();fixture(mark,1,3L,"IN_PROGRESS","2030-01-01 00:10:00","2030-01-01 00:20:00",false);fixture(mark,1,null,"OPEN","2030-01-01 00:05:00","2030-01-01 00:30:00",false);fixture(mark,2,4L,"ASSIGNED","2030-01-01 00:15:00","2030-01-01 01:00:00",false);
    String path="/api/operations/sla-inbox?filter=ALL&q="+mark;
    getAs("alice",path).andExpect(status().isForbidden());
    assertThat(node(getAs("agent",path).andExpect(status().isOk())).get("total").asInt()).isEqualTo(1);
    assertThat(node(getAs("supervisor",path).andExpect(status().isOk())).get("total").asInt()).isEqualTo(2);
    assertThat(node(getAs("otherlead",path).andExpect(status().isOk())).get("total").asInt()).isEqualTo(1);
    var all=node(getAs("admin",path).andExpect(status().isOk()));assertThat(all.get("total").asInt()).isEqualTo(3);assertThat(all.toString()).doesNotContain("内部客户细节");
    fixture("百分号%_固定文本",1,3L,"IN_PROGRESS","2030-01-01 00:10:00","2030-01-01 00:20:00",false);
    assertThat(node(mvc.perform(get("/api/operations/sla-inbox").param("filter","ALL").param("q","%_").header("Authorization","Bearer "+token("agent"))).andExpect(status().isOk())).get("total").asInt()).isEqualTo(1);
  }

  @Test void riskBoundariesUseOneClockAndPageByOutstandingDeadline()throws Exception{
    String mark="boundary-"+UUID.randomUUID();
    long breached=fixture(mark,1,3L,"ASSIGNED","2030-01-01 00:00:00","2030-01-02 00:00:00",false);
    long soon=fixture(mark,1,3L,"IN_PROGRESS","2029-12-31 23:00:00","2030-01-01 00:30:00",true);
    fixture(mark,1,3L,"IN_PROGRESS","2029-12-31 23:00:00","2030-01-01 00:30:01",true);
    fixture(mark,1,3L,"WAITING_CUSTOMER","2029-12-31 23:00:00","2029-12-31 23:00:00",true);
    fixture(mark,1,3L,"CLOSED","2029-12-31 23:00:00","2029-12-31 23:00:00",true);
    String path="/api/operations/sla-inbox?q="+mark;
    var over=node(getAs("agent",path+"&filter=BREACHED").andExpect(status().isOk()));assertThat(over.get("total").asInt()).isEqualTo(1);assertThat(over.get("items").get(0).get("id").asLong()).isEqualTo(breached);assertThat(over.get("asOf").asText()).isEqualTo("2030-01-01T00:00:00");
    var near=node(getAs("agent",path+"&filter=DUE_SOON").andExpect(status().isOk()));assertThat(near.get("total").asInt()).isEqualTo(1);assertThat(near.get("items").get(0).get("id").asLong()).isEqualTo(soon);
    assertThat(node(getAs("agent",path+"&filter=WAITING_CUSTOMER").andExpect(status().isOk())).get("total").asInt()).isEqualTo(1);
    var first=node(getAs("agent",path+"&filter=ALL&size=1").andExpect(status().isOk()));var second=node(getAs("agent",path+"&filter=ALL&size=1&page=2").andExpect(status().isOk()));assertThat(first.get("items").get(0).get("id").asLong()).isEqualTo(breached);assertThat(second.get("items").get(0).get("id").asLong()).isEqualTo(soon);assertThat(first.get("total").asInt()).isEqualTo(4);assertThat(first.get("summary")).isEqualTo(near.get("summary"));
    getAs("agent",path+"&filter=FAKE").andExpect(status().isBadRequest());getAs("agent",path+"&page=0").andExpect(status().isBadRequest());getAs("agent",path+"&size=101").andExpect(status().isBadRequest());getAs("agent","/api/operations/sla-inbox?q="+"x".repeat(101)).andExpect(status().isBadRequest());
  }
}
