package com.serviceflow.ai;

import com.serviceflow.ticket.TicketRequests;
import java.util.List;

public final class AiModels {
  private AiModels() {}

  public record Citation(String id, String title, String source, String text, double score) {}

  public record Proposal(
      String title,
      String description,
      TicketRequests.Category category,
      TicketRequests.Priority priority) {}

  public record Output(
      String mode,
      String intent,
      String answer,
      double confidence,
      Boolean handoff,
      List<Citation> citations,
      Proposal proposal) {}

  public record RunView(
      String id,
      String mode,
      String intent,
      String answer,
      double confidence,
      boolean handoff,
      List<Citation> citations,
      Proposal proposal,
      String status,
      Long ticketId) {}

  public static class Row {
    public String id;
    public long customerId;
    public String responseJson;
    public String messageHash;
    public String originalMessage;
    public boolean handoffRequested;
    public long latencyMs;
    public Long ticketId;
    public java.time.LocalDateTime createdAt;
  }
}
