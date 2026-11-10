package com.serviceflow.auth;

import com.serviceflow.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final UserMapper users;
  private final SessionService sessions;
  private final PasswordEncoder passwords;
  private final String dummy;
  private final com.serviceflow.ai.AiGateway ai;

  public AuthController(
      UserMapper users,
      SessionService sessions,
      PasswordEncoder passwords,
      com.serviceflow.ai.AiGateway ai) {
    this.ai = ai;
    this.users = users;
    this.sessions = sessions;
    this.passwords = passwords;
    dummy = passwords.encode("unavailable-user-timing-padding");
  }

  public record Login(
      @NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 128) String password) {}

  @PostMapping("/auth/login")
  public Map<String, Object> login(@Valid @RequestBody Login req, HttpServletRequest http) {
    if (req.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw ApiException.bad("密码不可超过 72 个 UTF-8 字节");
    sessions.throttle(req.username(), http.getRemoteAddr());
    User u = users.byUsername(req.username());
    boolean valid = passwords.matches(req.password(), u == null ? dummy : u.passwordHash);
    if (u == null || !valid) throw new ApiException(401, "INVALID_CREDENTIALS", "账号或密码错误");
    return Map.of("token", sessions.issue(u), "user", u, "expiresInSeconds", 7200);
  }

  @GetMapping("/auth/me")
  public User me(@AuthenticationPrincipal User u) {
    return u;
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@RequestHeader("Authorization") String auth) {
    sessions.revoke(auth.substring(7));
  }

  @GetMapping("/capabilities")
  public Map<String, Object> capabilities() {
    return Map.of(
        "milestone",
        "M3",
        "aiMode",
        ai.enabled() ? "enabled" : "disabled",
        "ragEnabled",
        ai.enabled(),
        "message",
        ai.enabled() ? "AI 已配置，实际检索及模型状态以每次响应为准" : "AI 尚未启用，请人工处理");
  }

  @GetMapping("/agents")
  public java.util.List<User> agents(@AuthenticationPrincipal User u, @RequestParam long teamId) {
    if (!u.is("ADMIN") && !(u.is("SUPERVISOR") && java.util.Objects.equals(u.teamId, teamId)))
      throw ApiException.forbidden();
    return users.agents(teamId);
  }
}
