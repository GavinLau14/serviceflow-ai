package com.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
class CollaborationConcurrencyTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;

  String token(String username) throws Exception {
    return json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(Map.of("username",username,"password","Test-Only-Password-123!"))))
        .andReturn().getResponse().getContentAsString()).get("token").asText();
  }

  @Test void handoffAndExecuteShareOneRunLockAndOneTicket() throws Exception {
    String token = token("alice"), id = UUID.randomUUID().toString(), text = "Concurrent handoff fixture";
    String response = json.writeValueAsString(Map.of("mode","rag","intent","create_ticket","answer","test","confidence",0.9,"handoff",false,
        "citations",List.of(Map.of("id","kb1","title","test","source","demo:kb1","text","test","score",0.9)),
        "proposal",Map.of("title",text,"description",text,"category","DEVICE","priority","NORMAL")));
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    db.update("INSERT INTO ai_runs(id,customer_id,response_json,message_hash,latency_ms,original_message) VALUES(?,1,?,?,0,?)",id,response,hash,text);
    Long created = null;
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
      java.util.function.Function<String,Callable<Long>> action = endpoint -> () -> {
        ready.countDown(); go.await();
        var result = mvc.perform(post("/api/ai/runs/"+id+"/"+endpoint).header("Authorization","Bearer "+token)
            .contentType("application/json").content(endpoint.equals("handoff") ? "{\"confirmed\":true,\"category\":\"DEVICE\"}" : "{\"confirmed\":true}")).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        return json.readTree(result.getContentAsString()).get("id").asLong();
      };
      var handoff = pool.submit(action.apply("handoff"));
      var execute = pool.submit(action.apply("execute"));
      assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); go.countDown();
      created = handoff.get(15,TimeUnit.SECONDS);
      assertThat(execute.get(15,TimeUnit.SECONDS)).isEqualTo(created);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM tickets WHERE description=?",Integer.class,text)).isEqualTo(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_events WHERE ticket_id=? AND action='HUMAN_HANDOFF'",Integer.class,created)).isEqualTo(1);
    } finally {
      db.update("DELETE FROM ai_runs WHERE id=?",id);
      if (created != null) {
        db.update("DELETE FROM ticket_events WHERE ticket_id=?",created);
        db.update("DELETE FROM tickets WHERE id=?",created);
      }
    }
  }
}
