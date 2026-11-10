package com.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkflowIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  String token(String user) throws Exception {
    return node(mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("username", user, "password", "Test-Only-Password-123!"))))
            .andExpect(status().isOk()))
        .get("token")
        .asText();
  }

  JsonNode node(ResultActions r) throws Exception {
    return json.readTree(
        r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  ResultActions send(String user, String path, Object body) throws Exception {
    return mvc.perform(
        post(path)
            .header("Authorization", "Bearer " + token(user))
            .contentType("application/json")
            .content(json.writeValueAsString(body)));
  }

  ResultActions read(String user, String path) throws Exception {
    return mvc.perform(get(path).header("Authorization", "Bearer " + token(user)));
  }

  JsonNode create(String user, String category) throws Exception {
    return node(
        send(
                user,
                "/api/tickets",
                Map.of(
                    "title",
                    "设备无法启动 " + UUID.randomUUID(),
                    "description",
                    "按下电源后无响应，已检查电源连接。",
                    "category",
                    category,
                    "priority",
                    "NORMAL"))
            .andExpect(status().isCreated()));
  }

  JsonNode assign(JsonNode t) throws Exception {
    return node(
        send(
                "supervisor",
                "/api/tickets/" + t.get("id").asLong() + "/assign",
                Map.of("assigneeId", 3, "version", t.get("version").asInt(), "confirmed", true))
            .andExpect(status().isOk()));
  }

  JsonNode move(String user, JsonNode t, String target, boolean confirmed) throws Exception {
    return node(
        send(
                user,
                "/api/tickets/" + t.get("id").asLong() + "/transition",
                Map.of(
                    "target",
                    target,
                    "version",
                    t.get("version").asInt(),
                    "confirmed",
                    confirmed,
                    "message",
                    "电源模块已检查并完成处理，请客户确认。"))
            .andExpect(status().isOk()));
  }

  @Test
  void anonymousDenied() throws Exception {
    mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
  }

  @Test
  void badPasswordDenied() throws Exception {
    mvc.perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void loginAndLogoutRevokesSession() throws Exception {
    String t = token("alice");
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t))
        .andExpect(status().isOk())
        .andExpect(jsonPath("role").value("CUSTOMER"))
        .andExpect(jsonPath("passwordHash").doesNotExist());
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + t))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void customerCannotReadOthersTicket() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    read("bob", "/api/tickets/" + t.get("id")).andExpect(status().isNotFound());
  }

  @Test
  void listAndCountAreScoped() throws Exception {
    create("alice", "DEVICE");
    create("bob", "DEVICE");
    JsonNode page = node(read("alice", "/api/tickets").andExpect(status().isOk()));
    assertThat(page.get("total").asInt()).isEqualTo(1);
    assertThat(page.get("items").get(0).get("customerId").asLong()).isEqualTo(1);
  }

  @Test
  void agentCannotSeeUnassigned() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    read("agent", "/api/tickets/" + t.get("id")).andExpect(status().isNotFound());
  }

  @Test
  void supervisorCannotSeeOtherTeam() throws Exception {
    JsonNode t = create("alice", "APPLIANCE");
    read("supervisor", "/api/tickets/" + t.get("id")).andExpect(status().isNotFound());
  }

  @Test
  void adminCanSeeAll() throws Exception {
    JsonNode t = create("alice", "APPLIANCE");
    read("admin", "/api/tickets/" + t.get("id")).andExpect(status().isOk());
  }

  @Test
  void customerCannotAssign() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    send(
            "alice",
            "/api/tickets/" + t.get("id") + "/assign",
            Map.of("assigneeId", 3, "version", 0, "confirmed", true))
        .andExpect(status().isForbidden());
  }

  @Test
  void assignmentRequiresExplicitConsent() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    send(
            "supervisor",
            "/api/tickets/" + t.get("id") + "/assign",
            Map.of("assigneeId", 3, "version", 0, "confirmed", false))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cannotAssignAcrossTeams() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    send(
            "supervisor",
            "/api/tickets/" + t.get("id") + "/assign",
            Map.of("assigneeId", 4, "version", 0, "confirmed", true))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cannotAssignCustomer() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    send(
            "supervisor",
            "/api/tickets/" + t.get("id") + "/assign",
            Map.of("assigneeId", 1, "version", 0, "confirmed", true))
        .andExpect(status().isBadRequest());
  }

  @Test
  void fullLifecycleWithCustomerRejectionAndConfirmation() throws Exception {
    JsonNode t = assign(create("alice", "DEVICE"));
    t = move("agent", t, "IN_PROGRESS", false);
    t = move("agent", t, "WAITING_CUSTOMER", false);
    t = move("alice", t, "IN_PROGRESS", true);
    t = move("agent", t, "WAITING_CUSTOMER", false);
    t = move("alice", t, "CLOSED", true);
    assertThat(t.get("status").asText()).isEqualTo("CLOSED");
    read("alice", "/api/tickets/" + t.get("id")).andExpect(jsonPath("events.length()").value(7));
  }

  @Test
  void staffCannotCloseOnBehalfOfCustomer() throws Exception {
    JsonNode t =
        move(
            "agent",
            move("agent", assign(create("alice", "DEVICE")), "IN_PROGRESS", false),
            "WAITING_CUSTOMER",
            false);
    send(
            "agent",
            "/api/tickets/" + t.get("id") + "/transition",
            Map.of(
                "target",
                "CLOSED",
                "version",
                t.get("version").asInt(),
                "confirmed",
                true,
                "message",
                "关闭"))
        .andExpect(status().isForbidden());
  }

  @Test
  void closeRequiresCustomerConsent() throws Exception {
    JsonNode t =
        move(
            "agent",
            move("agent", assign(create("alice", "DEVICE")), "IN_PROGRESS", false),
            "WAITING_CUSTOMER",
            false);
    send(
            "alice",
            "/api/tickets/" + t.get("id") + "/transition",
            Map.of(
                "target",
                "CLOSED",
                "version",
                t.get("version").asInt(),
                "confirmed",
                false,
                "message",
                "关闭"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidTransitionDenied() throws Exception {
    JsonNode t = assign(create("alice", "DEVICE"));
    send(
            "agent",
            "/api/tickets/" + t.get("id") + "/transition",
            Map.of("target", "WAITING_CUSTOMER", "version", 1, "message", "处理好了"))
        .andExpect(status().isConflict());
  }

  @Test
  void staleVersionDoesNotChangeTicket() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    assign(t);
    send(
            "supervisor",
            "/api/tickets/" + t.get("id") + "/assign",
            Map.of("assigneeId", 3, "version", 0, "confirmed", true))
        .andExpect(status().isConflict());
    read("supervisor", "/api/tickets/" + t.get("id"))
        .andExpect(jsonPath("ticket.version").value(1))
        .andExpect(jsonPath("events.length()").value(2));
  }

  @Test
  void internalNotesHiddenFromCustomer() throws Exception {
    JsonNode t = assign(create("alice", "DEVICE"));
    String path = "/api/tickets/" + t.get("id");
    send("agent", path + "/comments", Map.of("body", "仅内部可见的排查记录", "internal", true, "version", 1))
        .andExpect(status().isCreated());
    read("alice", path).andExpect(jsonPath("comments.length()").value(0));
    read("agent", path).andExpect(jsonPath("comments[0].body").value("仅内部可见的排查记录"));
  }

  @Test
  void customerCannotWriteInternalNote() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    send(
            "alice",
            "/api/tickets/" + t.get("id") + "/comments",
            Map.of("body", "隐藏", "internal", true, "version", 0))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthorizedCommentDenied() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    send("bob", "/api/tickets/" + t.get("id") + "/comments", Map.of("body", "test", "version", 0))
        .andExpect(status().isNotFound());
  }

  @Test
  void publicReplyVisible() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    send(
            "alice",
            "/api/tickets/" + t.get("id") + "/comments",
            Map.of("body", "补充故障细节", "version", 0))
        .andExpect(status().isCreated());
    read("alice", "/api/tickets/" + t.get("id"))
        .andExpect(jsonPath("comments[0].body").value("补充故障细节"));
  }

  @Test
  void createValidatesInput() throws Exception {
    send(
            "alice",
            "/api/tickets",
            Map.of("title", " ", "description", "x", "category", "DEVICE", "priority", "NORMAL"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void staffCannotCreateForCustomer() throws Exception {
    send(
            "agent",
            "/api/tickets",
            Map.of("title", "test", "description", "x", "category", "DEVICE", "priority", "NORMAL"))
        .andExpect(status().isForbidden());
  }

  @Test
  void paginationAndInvalidFiltersValidated() throws Exception {
    read("alice", "/api/tickets?page=0").andExpect(status().isBadRequest());
    read("alice", "/api/tickets?size=101").andExpect(status().isBadRequest());
    read("alice", "/api/tickets?status=BOGUS").andExpect(status().isBadRequest());
  }

  @Test
  void searchDoesNotTreatWildcardAsMatchAll() throws Exception {
    create("alice", "DEVICE");
    read("alice", "/api/tickets?q=%25").andExpect(jsonPath("total").value(0));
  }

  @Test
  void agentCannotQueryStaffDirectory() throws Exception {
    read("agent", "/api/agents?teamId=1").andExpect(status().isForbidden());
  }

  @Test
  void supervisorDirectoryRestrictedToOwnTeam() throws Exception {
    read("supervisor", "/api/agents?teamId=2").andExpect(status().isForbidden());
  }

  @Test
  void capabilitiesExplicitlyReportDisabledAI() throws Exception {
    read("alice", "/api/capabilities").andExpect(jsonPath("aiMode").value("disabled"));
  }

  @Test
  void requestCannotSpoofCustomerOrRole() throws Exception {
    send(
            "alice",
            "/api/tickets",
            Map.of(
                "title",
                "test",
                "description",
                "x",
                "category",
                "DEVICE",
                "priority",
                "NORMAL",
                "customerId",
                2,
                "role",
                "ADMIN"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidTokenIsRejected() throws Exception {
    mvc.perform(get("/api/tickets").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void otherAgentCannotSeeAssignedTicket() throws Exception {
    JsonNode t = assign(create("alice", "DEVICE"));
    read("otheragent", "/api/tickets/" + t.get("id")).andExpect(status().isNotFound());
  }

  @Test
  void longUnicodePasswordReturnsValidationError() throws Exception {
    sendLogin("密".repeat(40)).andExpect(status().isBadRequest());
  }

  ResultActions sendLogin(String password) throws Exception {
    return mvc.perform(
        post("/api/auth/login")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "alice", "password", password))));
  }

  @Test
  void mutationRequiresVersionEvenForFreshTicket() throws Exception {
    JsonNode t = create("alice", "DEVICE");
    String path = "/api/tickets/" + t.get("id");
    send("alice", path + "/comments", Map.of("body", "missing version"))
        .andExpect(status().isBadRequest());
    send("supervisor", path + "/assign", Map.of("assigneeId", 3, "confirmed", true))
        .andExpect(status().isBadRequest());
    String auth = token("alice");
    mvc.perform(
            post(path + "/comments")
                .header("Authorization", "Bearer " + auth)
                .contentType("application/json")
                .content("{\"body\":\"null version\",\"version\":null}"))
        .andExpect(status().isBadRequest());
  }
}
