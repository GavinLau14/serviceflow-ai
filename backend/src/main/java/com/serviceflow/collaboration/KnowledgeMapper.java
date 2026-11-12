package com.serviceflow.collaboration;

import com.serviceflow.auth.User;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface KnowledgeMapper {
  String SCOPE = "(#{u.role}='ADMIN' OR (#{u.role}='SUPERVISOR' AND t.team_id=#{u.teamId}) OR (#{u.role}='AGENT' AND t.assignee_id=#{u.id}))";
  String FILTER = " AND (#{status}='' OR k.status=#{status}) AND (#{q}='' OR LOCATE(#{q},k.title)>0 OR LOCATE(#{q},k.content)>0)";

  @Select("SELECT k.*,t.reference ticket_reference,t.title ticket_title,a.display_name author_name,r.display_name reviewer_name FROM knowledge_entries k JOIN tickets t ON t.id=k.ticket_id LEFT JOIN users a ON a.id=k.author_id LEFT JOIN users r ON r.id=k.reviewer_id WHERE " + SCOPE + FILTER + " ORDER BY k.created_at DESC,k.id DESC LIMIT #{limit} OFFSET #{offset}")
  List<Knowledge> page(@Param("u") User user,@Param("status") String status,@Param("q") String query,@Param("limit") int limit,@Param("offset") long offset);

  @Select("SELECT COUNT(*) FROM knowledge_entries k JOIN tickets t ON t.id=k.ticket_id WHERE " + SCOPE + FILTER)
  long count(@Param("u") User user,@Param("status") String status,@Param("q") String query);

  @Select("SELECT COUNT(*) FROM knowledge_entries k JOIN tickets t ON t.id=k.ticket_id WHERE " + SCOPE + " AND k.status='DRAFT'")
  long pending(@Param("u") User user);

  @Select("SELECT k.* FROM knowledge_entries k JOIN tickets t ON t.id=k.ticket_id WHERE " + SCOPE + " ORDER BY k.created_at DESC,k.id DESC LIMIT 100")
  List<Knowledge> list(@Param("u") User user);

  @Select("SELECT k.* FROM knowledge_entries k JOIN tickets t ON t.id=k.ticket_id WHERE k.id=#{id} AND " + SCOPE)
  Knowledge find(@Param("id") long id, @Param("u") User user);

  @Select("SELECT k.* FROM knowledge_entries k JOIN tickets t ON t.id=k.ticket_id WHERE k.id=#{id} AND " + SCOPE + " FOR UPDATE")
  Knowledge lock(@Param("id") long id, @Param("u") User user);

  @Options(flushCache=Options.FlushCachePolicy.TRUE)
  @Select("SELECT * FROM tickets WHERE id=#{id} FOR UPDATE")
  com.serviceflow.ticket.Ticket lockTicket(long id);

  @Select("SELECT * FROM knowledge_entries WHERE ticket_id=#{id} AND status='DRAFT' FOR UPDATE")
  Knowledge draft(long id);

  @Select("SELECT * FROM knowledge_entries WHERE id=#{id}")
  Knowledge byId(long id);

  @Insert("INSERT INTO knowledge_entries(ticket_id,team_id,title,content,author_id) VALUES(#{ticketId},#{teamId},#{title},#{content},#{authorId})")
  @Options(useGeneratedKeys=true,keyProperty="id")
  void insert(Knowledge row);

  @Update("UPDATE knowledge_entries SET status=#{status},reviewer_id=#{actor},review_note=#{note},reviewed_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND status='DRAFT'")
  int review(@Param("id") long id,@Param("status") String status,@Param("actor") long actor,@Param("note") String note);

  @Insert("INSERT INTO knowledge_events(knowledge_id,actor_id,action) VALUES(#{id},#{actor},#{action})")
  void event(@Param("id") long id,@Param("actor") long actor,@Param("action") String action);
}
