package com.serviceflow.collaboration;

import java.time.LocalDateTime;

public class Knowledge {
  public Long id;
  public long ticketId, teamId, authorId;
  public String title, content, status;
  public String reviewNote, authorName, reviewerName, ticketReference, ticketTitle;
  public Long reviewerId;
  public LocalDateTime createdAt, reviewedAt;
}
