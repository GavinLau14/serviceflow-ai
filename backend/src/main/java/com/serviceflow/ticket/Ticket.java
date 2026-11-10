package com.serviceflow.ticket;

import java.time.LocalDateTime;

public class Ticket {
  public Long id;
  public String reference, title, description, category, priority, status;
  public long customerId, teamId;
  public Long assigneeId;
  public int version;
  public LocalDateTime createdAt, updatedAt;
  public String customerName, assigneeName;
}
