package com.serviceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

/** Separate committed requests are required to exercise real MySQL row-lock contention. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeReviewConcurrencyTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  long ticketId;

  JsonNode node(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
  String login(String user) throws Exception {
    return node(mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(Map.of("username",user,"password","Test-Only-Password-123!"))))
        .andExpect(status().isOk())).get("token").asText();
  }
  ResultActions send(String token,String path,Object body) throws Exception {
    return mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)));
  }
  @AfterEach void cleanup() {
    if(ticketId==0)return;
    db.update("DELETE FROM knowledge_entries WHERE ticket_id=?",ticketId);
    db.update("DELETE FROM ticket_comments WHERE ticket_id=?",ticketId);
    db.update("DELETE FROM ticket_events WHERE ticket_id=?",ticketId);
    db.update("DELETE FROM tickets WHERE id=?",ticketId);
  }

  @Test void differentReviewNotesCannotOverwriteTheFirstCommittedDecision() throws Exception {
    String customer=login("alice"),agent=login("agent"),lead=login("supervisor"),admin=login("admin");
    JsonNode ticket=node(send(customer,"/api/tickets",Map.of("title","并发审核 "+UUID.randomUUID(),"description","明确标记的测试夹具","category","DEVICE","priority","NORMAL")).andExpect(status().isCreated()));
    ticketId=ticket.get("id").asLong();String path="/api/tickets/"+ticketId;
    ticket=node(send(lead,path+"/assign",Map.of("assigneeId",3,"version",ticket.get("version").asLong(),"confirmed",true)).andExpect(status().isOk()));
    for(String target:List.of("IN_PROGRESS","WAITING_CUSTOMER","CLOSED"))
      ticket=node(send(target.equals("CLOSED")?customer:agent,path+"/transition",Map.of("target",target,"version",ticket.get("version").asLong(),"confirmed",true,"message","并发测试完成复测并由客户确认")).andExpect(status().isOk()));
    long id=node(send(agent,path+"/knowledge",Map.of("title","待并发审核知识","content","人工整理的并发测试夹具","confirmed",true)).andExpect(status().isCreated())).get("id").asLong();
    String noteA="主管要求补充复测记录",noteB="管理员要求删除未经证实的结论";
    CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      Callable<Integer> a=()->{ready.countDown();go.await();return send(lead,"/api/knowledge/"+id+"/review",Map.of("approved",false,"confirmed",true,"note",noteA)).andReturn().getResponse().getStatus();};
      Callable<Integer> b=()->{ready.countDown();go.await();return send(admin,"/api/knowledge/"+id+"/review",Map.of("approved",false,"confirmed",true,"note",noteB)).andReturn().getResponse().getStatus();};
      var first=pool.submit(a);var second=pool.submit(b);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
      int statusA=first.get(20,TimeUnit.SECONDS),statusB=second.get(20,TimeUnit.SECONDS);
      assertThat(List.of(statusA,statusB)).containsExactlyInAnyOrder(200,409);
      var stored=db.queryForMap("SELECT status,review_note,reviewer_id FROM knowledge_entries WHERE id=?",id);
      assertThat(stored.get("status")).isEqualTo("REJECTED");
      assertThat(stored.get("review_note")).isEqualTo(statusA==200?noteA:noteB);
      assertThat(((Number)stored.get("reviewer_id")).longValue()).isEqualTo(statusA==200?5L:6L);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_events WHERE knowledge_id=? AND action='REJECTED'",Integer.class,id)).isEqualTo(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_audit WHERE ticket_id=? AND action='KNOWLEDGE_REJECTED'",Integer.class,ticketId)).isEqualTo(1);
    }
  }
}
