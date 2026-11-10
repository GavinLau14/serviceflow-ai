package com.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiExecutionConcurrencyTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;

  @Test
  void twoExecutionsCreateExactlyOneTicket() throws Exception {
    String token =
        json.readTree(
                mvc.perform(
                        post("/api/auth/login")
                            .contentType("application/json")
                            .content(
                                "{\"username\":\"alice\",\"password\":\"Test-Only-Password-123!\"}"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("token")
            .asText();
    String id = UUID.randomUUID().toString();
    String fixture =
        """
{"mode":"rag","intent":"create_ticket","answer":"test","confidence":0.9,"handoff":false,"citations":[],"proposal":{"title":"Concurrency fixture","description":"Concurrency fixture","category":"DEVICE","priority":"NORMAL"}}
""";
    db.update(
        "INSERT INTO ai_runs(id,customer_id,response_json,message_hash,latency_ms)"
            + " VALUES(?,1,?,REPEAT('0',64),0)",
        id,
        fixture);
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
      Callable<Long> action =
          () -> {
            ready.countDown();
            go.await();
            var r =
                mvc.perform(
                        post("/api/ai/runs/" + id + "/execute")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"confirmed\":true}"))
                    .andReturn()
                    .getResponse();
            assertThat(r.getStatus()).isEqualTo(200);
            return json.readTree(r.getContentAsString()).get("id").asLong();
          };
      var a = pool.submit(action);
      var b = pool.submit(action);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
    } finally {
      Long ticket = db.queryForObject("SELECT ticket_id FROM ai_runs WHERE id=?", Long.class, id);
      db.update("DELETE FROM ai_runs WHERE id=?", id);
      if (ticket != null) {
        db.update("DELETE FROM ticket_events WHERE ticket_id=?", ticket);
        db.update("DELETE FROM tickets WHERE id=?", ticket);
      }
    }
  }
}
