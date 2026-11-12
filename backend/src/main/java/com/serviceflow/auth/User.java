package com.serviceflow.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class User {
  public long id;
  public String username;
  public String displayName;
  @JsonIgnore public String passwordHash;
  @JsonIgnore public long credentialVersion;
  public String role;
  public Long teamId;
  @JsonIgnore public boolean active;

  public boolean is(String role) {
    return this.role.equals(role);
  }
}
