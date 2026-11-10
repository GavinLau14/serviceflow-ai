package com.serviceflow.ai;

import static com.serviceflow.ai.AiModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceflow.auth.User;
import com.serviceflow.common.ApiException;
import com.serviceflow.ticket.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiService {
  private final AiGateway gateway;
  private final AiMapper runs;
  private final ObjectMapper json;
  private final TicketService tickets;

  public AiService(AiGateway gateway, AiMapper runs, ObjectMapper json, TicketService tickets) {
    this.gateway = gateway;
    this.runs = runs;
    this.json = json;
    this.tickets = tickets;
  }

  private Output failure(String mode, String answer) {
    return new Output(mode, "unknown", answer, 0, true, List.of(), null);
  }

  public RunView assist(User user, String message) {
    if (!user.is("CUSTOMER")) throw ApiException.forbidden();
    long started = System.nanoTime();
    Output output;
    if (!gateway.enabled()) output = failure("disabled", "AI 未配置，尚未执行模型推理；请通过人工售后入口提交问题。");
    else
      try {
        output = validate(gateway.assist(message), message);
      } catch (Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        output = failure("error", "AI 服务暂不可用，未执行任何业务操作；请转人工处理。");
      }
    Row row = new Row();
    row.id = UUID.randomUUID().toString();
    row.customerId = user.id;
    row.latencyMs = (System.nanoTime() - started) / 1000000;
    try {
      row.responseJson = json.writeValueAsString(output);
      row.messageHash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(message.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    runs.insert(row);
    return view(row, output);
  }

  private Output validate(Output out, String message) {
    if (out == null
        || !Set.of("rag", "retrieval_only", "disabled", "error").contains(out.mode())
        || out.answer() == null
        || out.answer().isBlank()
        || out.answer().length() > 8000
        || out.intent() == null
        || out.intent().length() > 80
        || !Double.isFinite(out.confidence())
        || out.confidence() < 0
        || out.confidence() > 1
        || out.handoff() == null
        || out.citations() == null
        || out.citations().size() > 10) throw new IllegalArgumentException("Invalid AI response");
    for (Citation c : out.citations())
      if (c == null
          || c.id() == null
          || c.id().isBlank()
          || c.id().length() > 150
          || c.title() == null
          || c.title().isBlank()
          || c.title().length() > 200
          || c.source() == null
          || c.source().isBlank()
          || c.source().length() > 300
          || c.text() == null
          || c.text().isBlank()
          || c.text().length() > 5000
          || !Double.isFinite(c.score())) throw new IllegalArgumentException("Invalid citation");
    boolean evidence =
        out.mode().equals("rag")
            && out.confidence() >= 0.55
            && !out.handoff()
            && !out.citations().isEmpty();
    Proposal proposal = out.proposal();
    if (proposal != null) {
      if (!evidence) proposal = null;
      else if (proposal.title() == null
          || proposal.title().isBlank()
          || !message.startsWith(proposal.title())
          || proposal.title().length() > 160
          || !message.equals(proposal.description())
          || proposal.category() == null
          || proposal.priority() == null
          || !(proposal.priority() == TicketRequests.Priority.NORMAL
              || proposal.priority() == TicketRequests.Priority.HIGH))
        throw new IllegalArgumentException("Invalid proposal");
    }
    if (out.mode().equals("rag") && !evidence)
      return failure("error", "检索证据或模型可信度不足，已建议人工处理；未执行任何业务操作。");
    return new Output(
        out.mode(),
        out.intent(),
        out.answer(),
        out.confidence(),
        out.handoff(),
        out.citations(),
        proposal);
  }

  private Output decode(Row row) {
    try {
      return json.readValue(row.responseJson, Output.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid persisted AI run", e);
    }
  }

  private RunView view(Row row, Output out) {
    return new RunView(
        row.id,
        out.mode(),
        out.intent(),
        out.answer(),
        out.confidence(),
        out.handoff(),
        out.citations(),
        out.proposal(),
        row.ticketId != null
            ? "COMPLETED"
            : out.proposal() == null
                ? "NO_ACTION"
                : row.createdAt != null
                        && row.createdAt.isBefore(
                            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30))
                    ? "EXPIRED"
                    : "PENDING",
        row.ticketId);
  }

  @Transactional(readOnly = true)
  public List<RunView> history(User user) {
    return runs.history(user.id).stream().map(r -> view(r, decode(r))).toList();
  }

  @Transactional
  public Ticket execute(User user, String id, boolean confirmed) {
    if (!user.is("CUSTOMER")) throw ApiException.forbidden();
    Row row = runs.lock(id, user.id);
    if (row == null) throw ApiException.missing();
    if (!confirmed) throw ApiException.bad("请显式授权创建工单");
    if (row.ticketId != null) return tickets.require(user, row.ticketId);
    if (row.createdAt.isBefore(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30)))
      throw new ApiException(409, "PROPOSAL_EXPIRED", "提案已过期，请重新咨询");
    Output out = decode(row);
    Proposal p = out.proposal();
    if (p == null) throw ApiException.bad("此次 AI 结果没有可执行的建单提案");
    Ticket t =
        tickets.create(
            user,
            new TicketRequests.Create(p.title(), p.description(), p.category(), p.priority()));
    runs.complete(id, t.id);
    return t;
  }

  public Object index(User user) {
    if (!user.is("ADMIN")) throw ApiException.forbidden();
    try {
      return json.readTree(gateway.call("/index", Map.of()));
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(503, "AI_INDEX_UNAVAILABLE", "知识库索引不可用，请检查模型、Qdrant 与内部服务配置");
    }
  }
}
