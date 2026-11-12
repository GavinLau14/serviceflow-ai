package com.serviceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.serviceflow.auth.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordChangeIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired UserMapper users;
  @Autowired SessionService sessions;
  @Autowired PasswordEncoder encoder;
  @Autowired StringRedisTemplate redis;
  @Autowired PasswordService passwordService;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  final List<String> issued = Collections.synchronizedList(new ArrayList<>());
  long id;
  String username;
  static final String OLD = "Test-Only-Password-123!", NEW = "Changed-Test-Password-456!";
  static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis());

  @BeforeEach void account() {
    username="password-test-"+UUID.randomUUID().toString().substring(0,20);
    id=IDS.incrementAndGet();
    db.update("INSERT INTO users(id,username,display_name,password_hash,role) VALUES(?,?,'密码测试账号',?,'CUSTOMER')",id,username,encoder.encode(OLD));
  }
  @AfterEach void cleanup() {
    issued.forEach(sessions::revoke);
    db.update("DELETE FROM users WHERE id=?",id);
  }
  String login(String password) throws Exception {
    var r=mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(Map.of("username",username,"password",password))))
        .andExpect(status().isOk()).andReturn().getResponse();
    String token=json.readTree(r.getContentAsString()).get("token").asText();issued.add(token);return token;
  }
  ResultActions change(String token,Map<String,Object> body) throws Exception {
    return mvc.perform(post("/api/auth/password").header("Authorization","Bearer "+token)
        .contentType("application/json").content(json.writeValueAsString(body)));
  }
  Map<String,Object> body(String old,String next) { return Map.of("currentPassword",old,"newPassword",next,"confirmed",true); }
  ResultActions me(String token) throws Exception { return mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)); }

  @Test void successfulChangeInvalidatesEverySessionAndDoesNotExposeCredentialState() throws Exception {
    String a=login(OLD),b=login(OLD);
    User stale=users.byId(id);
    change(a,body(OLD,NEW)).andExpect(status().isNoContent());
    me(a).andExpect(status().isUnauthorized());me(b).andExpect(status().isUnauthorized());
    String late=sessions.issue(stale);issued.add(late);
    me(late).andExpect(status().isUnauthorized());
    String fresh=login(NEW);
    me(fresh).andExpect(status().isOk()).andExpect(jsonPath("passwordHash").doesNotExist())
        .andExpect(jsonPath("credentialVersion").doesNotExist());
    mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(Map.of("username",username,"password",OLD))))
        .andExpect(status().isUnauthorized());
    assertThat(encoder.matches(NEW,users.byId(id).passwordHash)).isTrue();
  }

  @Test void requiresCurrentPasswordAndExplicitConfirmationWithoutRevokingExistingSessions() throws Exception {
    String token=login(OLD);
    change(token,body("wrong-current-password",NEW)).andExpect(status().isBadRequest());
    change(token,Map.of("currentPassword",OLD,"newPassword",NEW,"confirmed",false)).andExpect(status().isBadRequest());
    change(token,Map.of("currentPassword",OLD,"newPassword",NEW)).andExpect(status().isBadRequest());
    change(token,body(OLD,OLD)).andExpect(status().isBadRequest());
    me(token).andExpect(status().isOk());
    assertThat(encoder.matches(OLD,users.byId(id).passwordHash)).isTrue();
  }

  @Test void enforcesUnicodeLengthAndBcryptByteLimit() throws Exception {
    String token=login(OLD);
    for(String next:new String[]{"short-pass","密".repeat(25),"😀".repeat(19)," ".repeat(12)})
      change(token,body(OLD,next)).andExpect(status().isBadRequest());
    change(token,body(OLD,"密".repeat(24))).andExpect(status().isNoContent());
    me(login("密".repeat(24))).andExpect(status().isOk());
  }

  @Test void legacySessionWorksOnlyBeforeCredentialVersionChanges() throws Exception {
    String token=login(OLD);
    String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    redis.opsForValue().set("sf-test:session:"+digest,Long.toString(id),Duration.ofMinutes(5));
    me(token).andExpect(status().isOk());
    change(token,body(OLD,NEW)).andExpect(status().isNoContent());
    me(token).andExpect(status().isUnauthorized());
  }

  @Test void cannotTargetAnotherUserAndUnauthenticatedChangeIsRejected() throws Exception {
    String token=login(OLD);
    Map<String,Object> req=new HashMap<>(body(OLD,NEW));req.put("userId",1);
    change(token,req).andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/password").contentType("application/json").content(json.writeValueAsString(body(OLD,NEW))))
        .andExpect(status().isUnauthorized());
    me(token).andExpect(status().isOk());
  }

  @Test void concurrentChangesCannotBothUseTheOldCredential() throws Exception {
    String a=login(OLD),b=login(OLD);
    var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      Callable<Integer> actionA=()->{start.await();return change(a,body(OLD,NEW)).andReturn().getResponse().getStatus();};
      Callable<Integer> actionB=()->{start.await();return change(b,body(OLD,"Another-Test-Password-789!" )).andReturn().getResponse().getStatus();};
      var first=pool.submit(actionA);var second=pool.submit(actionB);start.countDown();
      var statuses=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
      assertThat(statuses.stream().filter(s->s==204).count()).isEqualTo(1);
      assertThat(statuses.stream().filter(s->s==400 || s==401).count()).isEqualTo(1);
      me(a).andExpect(status().isUnauthorized());me(b).andExpect(status().isUnauthorized());
    }
  }

  @Test void transactionRollbackPreservesPasswordAndExistingSession() throws Exception {
    String token=login(OLD);
    var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
    assertThatThrownBy(()->tx.executeWithoutResult(status->{
      passwordService.change(users.byId(id),OLD,NEW);
      throw new IllegalStateException("fixture rollback");
    })).isInstanceOf(IllegalStateException.class).hasMessage("fixture rollback");
    assertThat(users.byId(id).credentialVersion).isZero();
    assertThat(encoder.matches(OLD,users.byId(id).passwordHash)).isTrue();
    me(token).andExpect(status().isOk());
  }
}
