package com.serviceflow.ticket;

import com.serviceflow.auth.User;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TicketMapper {
  Ticket find(@Param("id") long id, @Param("actor") User actor);

  List<Ticket> list(
      @Param("actor") User actor,
      @Param("status") String status,
      @Param("q") String q,
      @Param("limit") int limit,
      @Param("offset") int offset);

  long count(@Param("actor") User actor, @Param("status") String status, @Param("q") String q);

  void insert(Ticket ticket);

  int update(Ticket ticket);

  List<CommentView> comments(@Param("id") long id, @Param("customer") boolean customer);

  List<EventView> events(long id);

  @Insert(
      "INSERT INTO ticket_comments(ticket_id,author_id,body,internal)"
          + " VALUES(#{id},#{author},#{body},#{internal})")
  void comment(
      @Param("id") long id,
      @Param("author") long author,
      @Param("body") String body,
      @Param("internal") boolean internal);

  @Insert(
      "INSERT INTO ticket_events(ticket_id,actor_id,action,from_status,to_status,message)"
          + " VALUES(#{id},#{actor},#{action},#{from},#{to},#{message})")
  void event(
      @Param("id") long id,
      @Param("actor") long actor,
      @Param("action") String action,
      @Param("from") String from,
      @Param("to") String to,
      @Param("message") String message);

  record CommentView(
      long id,
      long authorId,
      String authorName,
      String body,
      boolean internal,
      java.time.LocalDateTime createdAt) {}

  record EventView(
      long id,
      String actorName,
      String action,
      String fromStatus,
      String toStatus,
      String message,
      java.time.LocalDateTime createdAt) {}
}
