package com.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

@SpringBootTest(properties = {"app.workflows.auto-assign=true", "app.workflows.agent-capacity=1", "app.workflows.scan-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationsIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.serviceflow.ticket.TicketService tickets;
  @Autowired com.serviceflow.auth.UserMapper users;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  final List<Long> created = Collections.synchronizedList(new ArrayList<>());

  @TestConfiguration
  static class TimeConfig {
    @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC); }
  }

  @AfterEach void cleanup() {
    for (long id : created) {
      jdbc.update("DELETE FROM ticket_comments WHERE ticket_id=?", id);
      jdbc.update("DELETE FROM ticket_events WHERE ticket_id=?", id);
      jdbc.update("DELETE FROM tickets WHERE id=?", id);
    }
  }

  String token(String user) throws Exception {
    return node(mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username", user, "password", "Test-Only-Password-123!")))).andExpect(status().isOk())).get("token").asText();
  }
  JsonNode node(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
  ResultActions read(String user,String path) throws Exception { return mvc.perform(get(path).header("Authorization", "Bearer " + token(user))); }
  ResultActions postAs(String user,String path,Object body) throws Exception { return mvc.perform(post(path).header("Authorization", "Bearer " + token(user)).contentType("application/json").content(json.writeValueAsString(body))); }
  JsonNode create() throws Exception {
    JsonNode t=node(postAs("alice","/api/tickets",Map.of("title","Operations " + UUID.randomUUID(),"description","Private customer details must never enter generic notifications", "category","DEVICE","priority","NORMAL")).andExpect(status().isCreated()));
    created.add(t.get("id").asLong()); return t;
  }

  @Test void initializesDeadlinesUsingClockAndRestrictsStaffViews() throws Exception {
    JsonNode t=create(); long id=t.get("id").asLong();
    read("agent","/api/tickets/"+id+"/sla").andExpect(status().isOk()).andExpect(jsonPath("firstResponseDue").value("2030-01-01T01:00:00")).andExpect(jsonPath("resolutionDue").value("2030-01-02T00:00:00"));
    read("alice","/api/tickets/"+id+"/sla").andExpect(status().isForbidden());
    read("otheragent","/api/tickets/"+id+"/audit").andExpect(status().isNotFound());
    read("agent","/api/tickets/"+id+"/audit").andExpect(status().isOk()).andExpect(jsonPath("$[0].action").value("CREATED"));
    postAs("agent","/api/operations/scan",Map.of("confirmed",true)).andExpect(status().isForbidden());
    postAs("supervisor","/api/operations/scan",Map.of("confirmed",false)).andExpect(status().isBadRequest());
  }

  @Test void simultaneousCreatesRespectCapacityAndStayInTeam() throws Exception {
    String customer=token("alice"); CountDownLatch go=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      Callable<JsonNode> action=()->{ go.await(); JsonNode t=node(mvc.perform(post("/api/tickets").header("Authorization","Bearer "+customer).contentType("application/json").content("{\"title\":\"Concurrent operations\",\"description\":\"Capacity check\",\"category\":\"DEVICE\",\"priority\":\"NORMAL\"}")).andExpect(status().isCreated())); created.add(t.get("id").asLong()); return t; };
      Future<JsonNode> a=pool.submit(action),b=pool.submit(action);go.countDown();
      List<JsonNode> result=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
      assertThat(result.stream().map(t->t.get("status").asText())).containsExactlyInAnyOrder("ASSIGNED","OPEN");
      assertThat(result.stream().filter(t->t.get("status").asText().equals("ASSIGNED")).findFirst().orElseThrow().get("assigneeId").asLong()).isEqualTo(3);
    }
  }

  @Test void responseIsPublicStaffOnlyAndEscalationIsOnceAndPausedWhileWaiting() throws Exception {
    JsonNode t=create(); long id=t.get("id").asLong();String path="/api/tickets/"+id;
    t=node(postAs("agent",path+"/comments",Map.of("body","Internal investigation","internal",true,"version",t.get("version").asInt())).andExpect(status().isCreated()));
    read("agent",path+"/sla").andExpect(jsonPath("firstRespondedAt").isEmpty());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox o JOIN ticket_audit a ON a.id=o.audit_id WHERE a.ticket_id=? AND a.action='INTERNAL_NOTE' AND o.user_id=1",Integer.class,id)).isZero();
    t=node(postAs("agent",path+"/transition",Map.of("target","IN_PROGRESS","message","Taking ownership","version",t.get("version").asInt())).andExpect(status().isOk()));
    t=node(postAs("agent",path+"/comments",Map.of("body","Please check your cable","version",t.get("version").asInt())).andExpect(status().isCreated()));
    read("agent",path+"/sla").andExpect(jsonPath("firstRespondedAt").value("2030-01-01T00:00:00"));
    t=node(postAs("agent",path+"/transition",Map.of("target","WAITING_CUSTOMER","message","Resolution available","version",t.get("version").asInt())).andExpect(status().isOk()));
    jdbc.update("UPDATE ticket_sla SET resolution_due='2029-12-31 00:00:00' WHERE ticket_id=?",id);
    postAs("supervisor","/api/operations/scan",Map.of("confirmed",true)).andExpect(status().isOk());
    read("agent",path+"/sla").andExpect(jsonPath("escalatedAt").isEmpty());
    postAs("alice",path+"/transition",Map.of("target","IN_PROGRESS","message","Still broken","confirmed",true,"version",t.get("version").asInt())).andExpect(status().isOk());
    for(int i=0;i<2;i++) postAs("supervisor","/api/operations/scan",Map.of("confirmed",true)).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_audit WHERE ticket_id=? AND action='SLA_ESCALATED'",Integer.class,id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT priority FROM tickets WHERE id=?",String.class,id)).isEqualTo("HIGH");
    int version=jdbc.queryForObject("SELECT version FROM tickets WHERE id=?",Integer.class,id);
    postAs("agent",path+"/comments",Map.of("body","Outdated response","version",0)).andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("SELECT version FROM tickets WHERE id=?",Integer.class,id)).isEqualTo(version);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_audit WHERE ticket_id=? AND action='COMMENTED'",Integer.class,id)).isEqualTo(1);
  }

  @Test void leastLoadSkipsInactiveAndOtherTeamAgents() throws Exception {
    JsonNode first=create();
    assertThat(first.get("assigneeId").asLong()).isEqualTo(3);
    jdbc.update("INSERT INTO users(id,username,display_name,password_hash,role,team_id,active) SELECT 3001,'operations-spare','Operations spare',password_hash,'AGENT',1,TRUE FROM users WHERE id=3");
    jdbc.update("INSERT INTO users(id,username,display_name,password_hash,role,team_id,active) SELECT 3000,'operations-inactive','Operations inactive',password_hash,'AGENT',1,FALSE FROM users WHERE id=3");
    try {
      JsonNode second=create();
      assertThat(second.get("assigneeId").asLong()).isEqualTo(3001);
      assertThat(create().get("status").asText()).isEqualTo("OPEN");
    } finally {
      cleanup();created.clear();
      jdbc.update("DELETE FROM users WHERE id IN (3000,3001)");
    }
  }

  @Test void concurrentScansEscalateOnceWithoutDuplicateDeliveries() throws Exception {
    long id=create().get("id").asLong();
    jdbc.update("UPDATE ticket_sla SET first_response_due='2029-12-31 00:00:00' WHERE ticket_id=?",id);
    String supervisor=token("supervisor");CountDownLatch go=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      Callable<Integer> action=()->{go.await();return mvc.perform(post("/api/operations/scan").header("Authorization","Bearer "+supervisor).contentType("application/json").content("{\"confirmed\":true}")).andReturn().getResponse().getStatus();};
      Future<Integer> a=pool.submit(action),b=pool.submit(action);go.countDown();
      assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsOnly(200);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_audit WHERE ticket_id=? AND action='SLA_ESCALATED'",Integer.class,id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE ticket_id=?",Integer.class,id)).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE ticket_id=?",Integer.class,id));
    assertThat(jdbc.queryForObject("SELECT priority FROM tickets WHERE id=?",String.class,id)).isEqualTo("HIGH");
  }

  @Test void rollbackRemovesTicketSlaAuditAndOutboxAtomically() {
    var customer=users.byUsername("alice");
    var id=new java.util.concurrent.atomic.AtomicLong();
    assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
      var t=tickets.create(customer,new com.serviceflow.ticket.TicketRequests.Create("Rollback operations","Transactional evidence",com.serviceflow.ticket.TicketRequests.Category.DEVICE,com.serviceflow.ticket.TicketRequests.Priority.NORMAL));
      id.set(t.id);
      throw new IllegalStateException("force outer workflow rollback");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(id.get()).isPositive();
    for(String table:List.of("tickets","ticket_sla","ticket_audit","notification_outbox","notifications")) {
      String column=table.equals("tickets")?"id":"ticket_id";
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Integer.class,id.get())).as(table).isZero();
    }
  }

  @Test void outboxDeliveryIsIdempotentAndNotificationReadIsOwnerScoped() throws Exception {
    JsonNode t=create();long id=t.get("id").asLong();
    for(int i=0;i<2;i++) postAs("supervisor","/api/operations/scan",Map.of("confirmed",true)).andExpect(status().isOk());
    JsonNode items=node(read("alice","/api/notifications").andExpect(status().isOk()));
    List<JsonNode> notices=new ArrayList<>();items.forEach(n->{if(n.get("ticketId").asLong()==id)notices.add(n);});
    assertThat(notices).isNotEmpty();
    assertThat(notices).allSatisfy(n->assertThat(n.get("body").asText()).doesNotContain("Private customer"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE ticket_id=?",Integer.class,id)).isEqualTo(notices.size()+jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE ticket_id=? AND user_id<>1",Integer.class,id));
    long notice=notices.getFirst().get("id").asLong();
    postAs("bob","/api/notifications/"+notice+"/read",Map.of()).andExpect(status().isNotFound());
    for(int i=0;i<2;i++) postAs("alice","/api/notifications/"+notice+"/read",Map.of()).andExpect(status().isOk()).andExpect(jsonPath("read").value(true));
  }
}
