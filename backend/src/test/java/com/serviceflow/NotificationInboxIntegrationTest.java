package com.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
class NotificationInboxIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;

  String token(String username) throws Exception {
    return node(mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(Map.of("username", username, "password", "Test-Only-Password-123!"))))
        .andExpect(status().isOk())).get("token").asText();
  }

  JsonNode node(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  ResultActions getAs(String user, String query) throws Exception {
    return mvc.perform(get("/api/notifications/inbox" + query).header("Authorization", "Bearer " + token(user)));
  }

  ResultActions readAll(String user, Object body) throws Exception {
    return mvc.perform(post("/api/notifications/read-all").header("Authorization", "Bearer " + token(user))
        .contentType("application/json").content(json.writeValueAsString(body)));
  }

  void seed(long userId, int count, int readCount) throws Exception {
    long ticketId = node(mvc.perform(post("/api/tickets").header("Authorization", "Bearer " + token("alice"))
        .contentType("application/json").content(json.writeValueAsString(Map.of("title", "Notification fixture",
            "description", "integration test", "category", "DEVICE", "priority", "NORMAL"))))
        .andExpect(status().isCreated())).get("id").asLong();
    for (int i = 0; i < count; i++) {
      db.update("INSERT INTO ticket_audit(ticket_id,action,message,created_at) VALUES(?,'TEST','notice fixture',UTC_TIMESTAMP())", ticketId);
      long auditId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
      db.update("INSERT INTO notification_outbox(audit_id,ticket_id,user_id,title,body,created_at) VALUES(?,?,?,'工单更新','notification fixture',UTC_TIMESTAMP())", auditId, ticketId, userId);
      long outboxId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
      db.update("INSERT INTO notifications(outbox_id,ticket_id,user_id,title,body,is_read,created_at) VALUES(?,?,?,'工单更新','notification fixture',?,UTC_TIMESTAMP())", outboxId, ticketId, userId, i < readCount);
    }
  }

  @Test void paginationReachesOlderThan100AndCountsOnlyCurrentOwner() throws Exception {
    seed(1, 108, 5);
    seed(2, 3, 0);
    var first = node(getAs("alice", "?page=1&size=20").andExpect(status().isOk()));
    var last = node(getAs("alice", "?page=6&size=20").andExpect(status().isOk()));
    assertThat(first.get("items").size()).isEqualTo(20);
    assertThat(first.get("total").asLong()).isEqualTo(108);
    assertThat(first.get("unreadTotal").asLong()).isEqualTo(103);
    assertThat(last.get("items").size()).isEqualTo(8);
    assertThat(first.get("items").get(19).get("id").asLong()).isGreaterThan(last.get("items").get(0).get("id").asLong());
    getAs("bob", "").andExpect(status().isOk()).andExpect(jsonPath("total").value(3));
    getAs("admin", "").andExpect(status().isOk()).andExpect(jsonPath("total").value(0));
  }

  @Test void unreadFilterAndEmptyPagesKeepAccurateGlobalCount() throws Exception {
    seed(1, 6, 2);
    getAs("alice", "?unreadOnly=true&size=2&page=2").andExpect(status().isOk())
        .andExpect(jsonPath("total").value(4)).andExpect(jsonPath("unreadTotal").value(4))
        .andExpect(jsonPath("items.length()").value(2)).andExpect(jsonPath("items[0].read").value(false));
    getAs("alice", "?page=2147483647&size=100").andExpect(status().isOk())
        .andExpect(jsonPath("items.length()").value(0)).andExpect(jsonPath("total").value(6));
  }

  @Test void readAllIsExplicitIdempotentAndCannotAffectAnotherAccount() throws Exception {
    seed(1, 105, 1);
    seed(2, 3, 0);
    readAll("alice", Map.of("confirmed", false)).andExpect(status().isBadRequest());
    readAll("alice", Map.of()).andExpect(status().isBadRequest());
    readAll("alice", Map.of("confirmed", true, "userId", 2)).andExpect(status().isBadRequest());
    readAll("alice", Map.of("confirmed", true)).andExpect(status().isOk()).andExpect(jsonPath("updated").value(104));
    readAll("alice", Map.of("confirmed", true)).andExpect(status().isOk()).andExpect(jsonPath("updated").value(0));
    getAs("alice", "?unreadOnly=true").andExpect(status().isOk())
        .andExpect(jsonPath("total").value(0)).andExpect(jsonPath("unreadTotal").value(0));
    getAs("bob", "").andExpect(status().isOk()).andExpect(jsonPath("unreadTotal").value(3));
  }

  @Test void validatesBoundsAndRequiresAuthentication() throws Exception {
    for (String query : new String[]{"?page=0", "?size=0", "?size=101", "?page=-1", "?unreadOnly=invalid"})
      getAs("alice", query).andExpect(status().isBadRequest());
    mvc.perform(get("/api/notifications/inbox")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/notifications/read-all").contentType("application/json").content("{\"confirmed\":true}"))
        .andExpect(status().isUnauthorized());
  }
}
