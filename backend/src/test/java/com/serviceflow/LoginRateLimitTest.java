package com.serviceflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.auth.login-limit=3", "app.auth.namespace=sf-test-limit"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRateLimitTest {
  @Autowired MockMvc mvc;
  @Autowired TestClock clock;
  static class TestClock extends Clock {
    volatile Instant now=Instant.parse("2031-01-01T00:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
    public Instant instant(){return now;}
  }
  @TestConfiguration static class TimeConfig {
    @Bean @Primary TestClock limitClock(){return new TestClock();}
  }

  @Test
  void fourthLoginAttemptIsRateLimited() throws Exception {
    String ip = "test-" + UUID.randomUUID();
    for (int i = 0; i < 4; i++)
      mvc.perform(
              post("/api/auth/login")
                  .with(
                      r -> {
                        r.setRemoteAddr(ip);
                        return r;
                      })
                  .contentType("application/json")
                  .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
          .andExpect(i < 3 ? status().isUnauthorized() : status().isTooManyRequests());
  }

  @Test
  void aNewMinuteStartsANewBucket() throws Exception {
    String ip="boundary-"+UUID.randomUUID();
    clock.now=Instant.parse("2031-01-01T00:00:59Z");
    for(int i=0;i<4;i++)
      mvc.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(ip);return r;})
          .contentType("application/json").content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
          .andExpect(i<3?status().isUnauthorized():status().isTooManyRequests());
    clock.now=Instant.parse("2031-01-01T00:01:00Z");
    mvc.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(ip);return r;})
        .contentType("application/json").content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }
}
