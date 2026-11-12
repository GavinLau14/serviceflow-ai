package com.serviceflow.auth;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
  @Select("SELECT * FROM users WHERE username=#{username} AND active=TRUE")
  User byUsername(String username);

  @Select("SELECT * FROM users WHERE id=#{id} AND active=TRUE")
  User byId(long id);

  @Options(flushCache=Options.FlushCachePolicy.TRUE)
  @Select("SELECT * FROM users WHERE id=#{id} AND active=TRUE FOR UPDATE")
  User lockActive(long id);

  @Update("UPDATE users SET password_hash=#{hash},credential_version=credential_version+1 WHERE id=#{id} AND credential_version=#{version} AND active=TRUE")
  int changeCredential(@Param("id") long id,@Param("version") long version,@Param("hash") String hash);

  @Select(
      "SELECT * FROM users WHERE role='AGENT' AND team_id=#{teamId} AND active=TRUE ORDER BY id")
  List<User> agents(long teamId);

  @Insert(
      "INSERT INTO users(id,username,display_name,password_hash,role,team_id)"
          + " VALUES(#{id},#{username},#{displayName},#{passwordHash},#{role},#{teamId})")
  void insert(User user);
}
