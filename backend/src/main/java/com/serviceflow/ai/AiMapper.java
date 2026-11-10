package com.serviceflow.ai;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AiMapper {
  @Insert(
      "INSERT INTO ai_runs(id,customer_id,response_json,message_hash,latency_ms,original_message)"
          + " VALUES(#{id},#{customerId},#{responseJson},#{messageHash},#{latencyMs},#{originalMessage})")
  void insert(AiModels.Row row);

  @Select(
      "SELECT * FROM ai_runs WHERE customer_id=#{owner} ORDER BY created_at DESC,id DESC LIMIT 20")
  List<AiModels.Row> history(long owner);

  @Select("SELECT * FROM ai_runs WHERE id=#{id} AND customer_id=#{owner} FOR UPDATE")
  AiModels.Row lock(@Param("id") String id, @Param("owner") long owner);

  @Update("UPDATE ai_runs SET handoff_requested=TRUE WHERE id=#{id}")
  void handoffRequested(String id);

  @Update("UPDATE ai_runs SET ticket_id=#{ticket} WHERE id=#{id}")
  void complete(@Param("id") String id, @Param("ticket") long ticket);
}
