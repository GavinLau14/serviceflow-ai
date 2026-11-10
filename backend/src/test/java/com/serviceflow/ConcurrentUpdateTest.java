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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentUpdateTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  String login(String name) throws Exception {
    var r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("username", name, "password", "Test-Only-Password-123!"))))
            .andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
  }

  MockHttpServletRequestBuilder assign(long id, String token) {
    return post("/api/tickets/" + id + "/assign")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content("{\"assigneeId\":3,\"version\":0,\"confirmed\":true}");
  }

  @Test
  void simultaneousAssignmentsHaveOneWinnerAndOneEvent() throws Exception {
    String customer = login("alice"), supervisor = login("supervisor");
    var created =
        mvc.perform(
                post("/api/tickets")
                    .header("Authorization", "Bearer " + customer)
                    .contentType("application/json")
                    .content(
                        "{\"title\":\"Concurrent test\",\"description\":\"CAS"
                            + " protection\",\"category\":\"DEVICE\",\"priority\":\"NORMAL\"}"))
            .andReturn();
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
      Callable<Integer> action =
          () -> {
            ready.countDown();
            go.await();
            return mvc.perform(assign(id, supervisor)).andReturn().getResponse().getStatus();
          };
      Future<Integer> a = pool.submit(action), b = pool.submit(action);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(jdbc.queryForObject("SELECT version FROM tickets WHERE id=?", Integer.class, id))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ticket_events WHERE ticket_id=?", Integer.class, id))
          .isEqualTo(2);
    } finally {
      jdbc.update("DELETE FROM ticket_events WHERE ticket_id=?", id);
      jdbc.update("DELETE FROM tickets WHERE id=?", id);
    }
  }
}
