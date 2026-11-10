package com.serviceflow.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoUsers implements CommandLineRunner {
  private final UserMapper users;
  private final PasswordEncoder encoder;
  private final String password;

  public DemoUsers(
      UserMapper users, PasswordEncoder encoder, @Value("${app.demo.password}") String password) {
    this.users = users;
    this.encoder = encoder;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (password.length() < 16)
      throw new IllegalStateException("Demo password must have at least 16 characters");
    add(1, "alice", "林晓 · 客户", "CUSTOMER", null);
    add(2, "bob", "陈明 · 客户", "CUSTOMER", null);
    add(3, "agent", "苏晴 · 客服", "AGENT", 1L);
    add(4, "otheragent", "周越 · 家电客服", "AGENT", 2L);
    add(5, "supervisor", "许宁 · 主管", "SUPERVISOR", 1L);
    add(6, "admin", "系统管理员", "ADMIN", null);
    add(7, "otherlead", "家电组主管", "SUPERVISOR", 2L);
  }

  private void add(long id, String name, String display, String role, Long team) {
    if (users.byUsername(name) != null) return;
    User u = new User();
    u.id = id;
    u.username = name;
    u.displayName = display;
    u.role = role;
    u.teamId = team;
    u.passwordHash = encoder.encode(password);
    users.insert(u);
  }
}
