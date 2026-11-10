package com.serviceflow.auth;

import com.serviceflow.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
  private final StringRedisTemplate redis;
  private final UserMapper users;
  private final int ttl;
  private final int limit;
  private final String namespace;

  public SessionService(
      StringRedisTemplate redis,
      UserMapper users,
      @Value("${app.auth.ttl-minutes}") int ttl,
      @Value("${app.auth.login-limit}") int limit,
      @Value("${app.auth.namespace:sf}") String namespace) {
    this.redis = redis;
    this.users = users;
    this.ttl = ttl;
    this.limit = limit;
    this.namespace = namespace;
  }

  static String digest(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public void throttle(String username, String ip) {
    // Fixed time buckets have no expiry race if a process dies between INCR and EXPIRE.
    String key = namespace + ":login:" + digest(ip) + ":" + (System.currentTimeMillis() / 60000);
    Long count = redis.opsForValue().increment(key);
    redis.expire(key, Duration.ofMinutes(2));
    if (count != null && count > limit)
      throw new ApiException(429, "RATE_LIMITED", "登录过于频繁，请一分钟后重试");
  }

  public String issue(User user) {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    redis
        .opsForValue()
        .set(
            namespace + ":session:" + digest(token),
            Long.toString(user.id),
            Duration.ofMinutes(ttl));
    return token;
  }

  public User resolve(String token) {
    if (token == null || token.length() != 43) return null;
    String id = redis.opsForValue().get(namespace + ":session:" + digest(token));
    return id == null ? null : users.byId(Long.parseLong(id));
  }

  public void revoke(String token) {
    redis.delete(namespace + ":session:" + digest(token));
  }
}
