package com.serviceflow.ticket;

import jakarta.validation.constraints.*;

public final class TicketRequests {
  private TicketRequests() {}

  public enum Category {
    DEVICE,
    APPLIANCE
  }

  public enum Priority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
  }

  public enum Status {
    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    WAITING_CUSTOMER,
    CLOSED
  }

  public record Create(
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 10000) String description,
      @NotNull Category category,
      @NotNull Priority priority) {}

  public record Assign(
      @Positive long assigneeId, @NotNull @Min(0) Integer version, boolean confirmed) {}

  public record Transition(
      @NotNull Status target,
      @NotNull @Min(0) Integer version,
      boolean confirmed,
      @NotBlank @Size(max = 2000) String message) {}

  public record Comment(
      @NotBlank @Size(max = 5000) String body,
      boolean internal,
      @NotNull @Min(0) Integer version) {}
}
