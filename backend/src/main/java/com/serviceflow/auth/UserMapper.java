package com.serviceflow.auth;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
  @Select("SELECT * FROM users WHERE username=#{username} AND active=TRUE")
  User byUsername(String username);

  @Select("SELECT * FROM users WHERE id=#{id} AND active=TRUE")
  User byId(long id);

  @Select(
      "SELECT * FROM users WHERE role='AGENT' AND team_id=#{teamId} AND active=TRUE ORDER BY id")
  List<User> agents(long teamId);

  @Insert(
      "INSERT INTO users(id,username,display_name,password_hash,role,team_id)"
          + " VALUES(#{id},#{username},#{displayName},#{passwordHash},#{role},#{teamId})")
  void insert(User user);
}
