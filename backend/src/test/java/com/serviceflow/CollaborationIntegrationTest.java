package com.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CollaborationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;

  String token(String user) throws Exception {
    return node(mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(Map.of("username", user, "password", "Test-Only-Password-123!"))))
        .andExpect(status().isOk())).get("token").asText();
  }
  JsonNode node(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }
  ResultActions send(String user, String path, Object body) throws Exception {
    return mvc.perform(post(path).header("Authorization", "Bearer " + token(user))
        .contentType("application/json").content(json.writeValueAsString(body)));
  }
  ResultActions read(String user, String path) throws Exception {
    return mvc.perform(get(path).header("Authorization", "Bearer " + token(user)));
  }
  String run() throws Exception {
    return node(send("alice", "/api/ai/assist", Map.of("message", "设备无法开机；联系电话不得进入知识内容"))
        .andExpect(status().isOk())).get("id").asText();
  }
  long closedTicket() throws Exception {
    long id = node(send("alice", "/api/tickets", Map.of("title", "Manual resolution source", "description", "PRIVATE CUSTOMER DETAILS", "category", "DEVICE", "priority", "NORMAL"))
        .andExpect(status().isCreated())).get("id").asLong();
    db.update("UPDATE tickets SET status='CLOSED',assignee_id=3 WHERE id=?", id);
    return id;
  }
  Map<String,Object> draft() { return Map.of("title", "电源故障处理", "content", "检查电源适配器并更换已损坏模块。", "confirmed", true); }

  @Test void explicitHandoffWorksWhenAiDisabledAndUsesStoredInput() throws Exception {
    String id = run(), path = "/api/ai/runs/" + id + "/handoff";
    send("alice", path, Map.of("confirmed", true)).andExpect(status().isBadRequest());
    send("alice", path, Map.of("confirmed", false, "category", "DEVICE")).andExpect(status().isBadRequest());
    send("bob", path, Map.of("confirmed", true, "category", "DEVICE")).andExpect(status().isNotFound());
    send("agent", path, Map.of("confirmed", true, "category", "DEVICE")).andExpect(status().isForbidden());
    JsonNode ticket = node(send("alice", path, Map.of("confirmed", true, "category", "DEVICE")).andExpect(status().isOk()));
    assertThat(ticket.get("description").asText()).isEqualTo("设备无法开机；联系电话不得进入知识内容");
    assertThat(node(send("alice", path, Map.of("confirmed", true, "category", "DEVICE")).andExpect(status().isOk())).get("id"))
        .isEqualTo(ticket.get("id"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_events WHERE ticket_id=? AND action='HUMAN_HANDOFF' AND actor_id=1", Integer.class, ticket.get("id").asLong())).isEqualTo(1);
  }
  @Test void retrievalOnlyHumanRequestDoesNotDependOnAiProposalOrConfidence() throws Exception {
    String id = run();
    db.update("UPDATE ai_runs SET response_json=? WHERE id=?",
        "{\"mode\":\"retrieval_only\",\"intent\":\"unknown\",\"answer\":\"检索结果\",\"confidence\":0,\"handoff\":true,\"citations\":[],\"proposal\":null}", id);
    assertThat(db.queryForObject("SELECT ticket_id FROM ai_runs WHERE id=?", Long.class, id)).isNull();
    send("alice", "/api/ai/runs/" + id + "/handoff", Map.of("confirmed", true, "category", "DEVICE")).andExpect(status().isOk());
    assertThat(db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(response_json,'$.mode')) FROM ai_runs WHERE id=?", String.class,id)).isEqualTo("retrieval_only");
  }
  @Test void existingExecutedTicketIsReusedAndStillRecordsExplicitHandoff() throws Exception {
    String id = run();
    long ticket = closedTicket();
    db.update("UPDATE ai_runs SET ticket_id=? WHERE id=?",ticket,id);
    send("alice", "/api/ai/runs/" + id + "/handoff", Map.of("confirmed", true, "category", "DEVICE")).andExpect(status().isOk()).andExpect(jsonPath("id").value(ticket));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_events WHERE ticket_id=? AND action='HUMAN_HANDOFF'",Integer.class,ticket)).isEqualTo(1);
  }
  @Test void oldRunWithoutOriginalOrValidatedProposalCannotHandoff() throws Exception {
    String id = run();
    db.update("UPDATE ai_runs SET original_message=NULL WHERE id=?", id);
    send("alice", "/api/ai/runs/" + id + "/handoff", Map.of("confirmed", true, "category", "DEVICE"))
        .andExpect(status().isConflict()).andExpect(jsonPath("code").value("ORIGINAL_MESSAGE_UNAVAILABLE"));
  }
  @Test void closedTicketCreatesManualDraftAndDoesNotCopyPrivateDescription() throws Exception {
    long ticket = closedTicket();
    JsonNode knowledge = node(send("agent", "/api/tickets/" + ticket + "/knowledge", draft()).andExpect(status().isCreated()));
    assertThat(knowledge.get("status").asText()).isEqualTo("DRAFT");
    assertThat(knowledge.get("content").asText()).doesNotContain("PRIVATE");
    assertThat(node(send("supervisor", "/api/tickets/" + ticket + "/knowledge", draft()).andExpect(status().isCreated())).get("id"))
        .isEqualTo(knowledge.get("id"));
    send("supervisor", "/api/knowledge/" + knowledge.get("id") + "/review", Map.of("confirmed", true)).andExpect(status().isBadRequest());
    send("agent", "/api/knowledge/" + knowledge.get("id") + "/review", Map.of("approved", true, "confirmed", true)).andExpect(status().isForbidden());
    send("otherlead", "/api/knowledge/" + knowledge.get("id") + "/review", Map.of("approved", true, "confirmed", true)).andExpect(status().isNotFound());
    send("supervisor", "/api/knowledge/" + knowledge.get("id") + "/review", Map.of("approved", true, "confirmed", false)).andExpect(status().isBadRequest());
    send("supervisor", "/api/knowledge/" + knowledge.get("id") + "/review", Map.of("approved", true, "confirmed", true)).andExpect(status().isOk()).andExpect(jsonPath("status").value("PUBLISHED"));
    send("admin", "/api/knowledge/" + knowledge.get("id") + "/review", Map.of("approved", false, "confirmed", true)).andExpect(status().isConflict());
  }
  @Test void draftingRequiresClosedTicketManualContentAndStaffScope() throws Exception {
    long id = closedTicket();
    send("alice", "/api/tickets/" + id + "/knowledge", draft()).andExpect(status().isForbidden());
    send("otheragent", "/api/tickets/" + id + "/knowledge", draft()).andExpect(status().isNotFound());
    send("otherlead", "/api/tickets/" + id + "/knowledge", draft()).andExpect(status().isNotFound());
    send("agent", "/api/tickets/" + id + "/knowledge", Map.of("title", "title", "confirmed", true)).andExpect(status().isBadRequest());
    send("agent", "/api/tickets/" + id + "/knowledge", Map.of("title", "title", "content", "manual", "confirmed", false)).andExpect(status().isBadRequest());
    db.update("UPDATE tickets SET status='IN_PROGRESS' WHERE id=?", id);
    send("agent", "/api/tickets/" + id + "/knowledge", draft()).andExpect(status().isConflict());
  }
  @Test void knowledgeListIsStaffAndTicketScopedAndRejectedDraftCanBeRevised() throws Exception {
    long ticket = closedTicket();
    long id = node(send("agent", "/api/tickets/" + ticket + "/knowledge", draft()).andExpect(status().isCreated())).get("id").asLong();
    read("alice", "/api/knowledge").andExpect(status().isForbidden());
    read("otherlead", "/api/knowledge").andExpect(status().isOk()).andExpect(jsonPath("length()").value(0));
    read("agent", "/api/knowledge").andExpect(status().isOk()).andExpect(jsonPath("[0].ticketId").value(ticket));
    send("supervisor", "/api/knowledge/" + id + "/review", Map.of("approved", false, "confirmed", true,"note","请补充实际复测结果")).andExpect(status().isOk()).andExpect(jsonPath("status").value("REJECTED"));
    long revised = node(send("agent", "/api/tickets/" + ticket + "/knowledge", draft()).andExpect(status().isCreated())).get("id").asLong();
    assertThat(revised).isNotEqualTo(id);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_events WHERE knowledge_id=?", Integer.class, id)).isEqualTo(2);
  }
}
