package com.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiIntegrationTest {
  static HttpServer server;
  static volatile String output;

  static {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/assist",
          e -> {
            byte[] b = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
          });
      server.createContext(
          "/index",
          e -> {
            byte[] b = "{\"status\":\"indexed\"}".getBytes();
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
          });
      server.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.ai.enabled", () -> true);
    r.add("app.ai.url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    r.add("app.ai.token", () -> "test-internal-token");
  }

  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @BeforeEach
  void fixture() {
    output =
        """
{"mode":"rag","intent":"create_ticket","answer":"演示建议","confidence":0.9,"handoff":false,"citations":[{"id":"kb1","title":"演示文档","source":"demo:kb1","text":"检查电源连接","score":0.9}],"proposal":{"title":"设备无法开机","description":"设备无法开机","category":"DEVICE","priority":"NORMAL"}}
""";
  }

  String token(String user) throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/api/auth/login")
                        .contentType("application/json")
                        .content(
                            json.writeValueAsString(
                                Map.of("username", user, "password", "Test-Only-Password-123!"))))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("token")
        .asText();
  }

  ResultActions postAs(String u, String path, Object body) throws Exception {
    return mvc.perform(
        post(path)
            .header("Authorization", "Bearer " + token(u))
            .contentType("application/json")
            .content(json.writeValueAsString(body)));
  }

  JsonNode assist() throws Exception {
    return json.readTree(
        postAs("alice", "/api/ai/assist", Map.of("message", "设备无法开机"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void proposalNeverAutomaticallyCreatesTicket() throws Exception {
    var r = assist();
    assertThat(r.get("status").asText()).isEqualTo("PENDING");
    assertThat(r.get("ticketId").isNull()).isTrue();
  }

  @Test
  void executeRequiresConsentAndIsIdempotent() throws Exception {
    var r = assist();
    String p = "/api/ai/runs/" + r.get("id").asText() + "/execute";
    postAs("alice", p, Map.of("confirmed", false)).andExpect(status().isBadRequest());
    var a = postAs("alice", p, Map.of("confirmed", true)).andExpect(status().isOk()).andReturn();
    var b = postAs("alice", p, Map.of("confirmed", true)).andExpect(status().isOk()).andReturn();
    assertThat(json.readTree(a.getResponse().getContentAsString()).get("id"))
        .isEqualTo(json.readTree(b.getResponse().getContentAsString()).get("id"));
  }

  @Test
  void otherCustomerCannotExecuteProposal() throws Exception {
    var r = assist();
    postAs("bob", "/api/ai/runs/" + r.get("id").asText() + "/execute", Map.of("confirmed", true))
        .andExpect(status().isNotFound());
  }

  @Test
  void clientCannotReplaceStoredProposal() throws Exception {
    var r = assist();
    postAs(
            "alice",
            "/api/ai/runs/" + r.get("id").asText() + "/execute",
            Map.of("confirmed", true, "customerId", 2))
        .andExpect(status().isBadRequest());
  }

  @Test
  void staffCannotAskOnBehalfOfCustomer() throws Exception {
    postAs("agent", "/api/ai/assist", Map.of("message", "设备无法开机"))
        .andExpect(status().isForbidden());
  }

  @Test
  void historyIsOwnerScoped() throws Exception {
    assist();
    mvc.perform(get("/api/ai/runs").header("Authorization", "Bearer " + token("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("length()").value(0));
  }

  @Test
  void lowConfidenceCannotExecute() throws Exception {
    output = output.replace("0.9", "0.1");
    var r = assist();
    assertThat(r.get("proposal").isNull()).isTrue();
    assertThat(r.get("handoff").asBoolean()).isTrue();
    assertThat(r.get("mode").asText()).isEqualTo("rag");
    assertThat(r.get("citations").size()).isEqualTo(1);
  }

  @Test
  void supportedHandoffRetainsCitationsWithoutAuthorizingDraft() throws Exception {
    output = output.replace("\"handoff\":false", "\"handoff\":true");
    var r = assist();
    assertThat(r.get("mode").asText()).isEqualTo("rag");
    assertThat(r.get("handoff").asBoolean()).isTrue();
    assertThat(r.get("citations").size()).isEqualTo(1);
    assertThat(r.get("confidence").asDouble()).isZero();
    assertThat(r.get("answer").asText()).contains("转人工");
    assertThat(r.get("proposal").isNull()).isTrue();
    postAs("alice", "/api/ai/runs/" + r.get("id").asText() + "/execute", Map.of("confirmed", true))
        .andExpect(status().isBadRequest());
  }

  @Test
  void noCitationsFailsClosed() throws Exception {
    output = output.replaceFirst("\\[.*?\\]", "[]");
    assertThat(assist().get("proposal").isNull()).isTrue();
  }

  @Test
  void malformedProviderResponseLoggedAsFailure() throws Exception {
    output = "not json";
    var r = assist();
    assertThat(r.get("mode").asText()).isEqualTo("error");
    assertThat(r.get("proposal").isNull()).isTrue();
  }

  @Test
  void retrievalOnlyNeverHasExecutableProposal() throws Exception {
    output = output.replace("\"rag\"", "\"retrieval_only\"");
    assertThat(assist().get("proposal").isNull()).isTrue();
  }

  @Test
  void onlyAdminCanIndex() throws Exception {
    postAs("alice", "/api/ai/index", Map.of()).andExpect(status().isForbidden());
    postAs("admin", "/api/ai/index", Map.of()).andExpect(status().isOk());
  }

  @Test
  void expiredProposalCannotExecute() throws Exception {
    var r = assist();
    db.update(
        "UPDATE ai_runs SET created_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 31 MINUTE) WHERE id=?",
        r.get("id").asText());
    postAs("alice", "/api/ai/runs/" + r.get("id").asText() + "/execute", Map.of("confirmed", true))
        .andExpect(status().isConflict());
  }

  @Test
  void fabricatedDescriptionIsRejected() throws Exception {
    output = output.replace("\"description\":\"设备无法开机\"", "\"description\":\"已同意退款\"");
    assertThat(assist().get("mode").asText()).isEqualTo("error");
  }

  @Test
  void missingHandoffRejected() throws Exception {
    output = output.replace("\"handoff\":false,", "");
    assertThat(assist().get("mode").asText()).isEqualTo("error");
  }

  @Test
  void blankCitationsCannotAuthorizeTool() throws Exception {
    output = output.replace("\"id\":\"kb1\"", "\"id\":\" \"");
    assertThat(assist().get("proposal").isNull()).isTrue();
  }
}
