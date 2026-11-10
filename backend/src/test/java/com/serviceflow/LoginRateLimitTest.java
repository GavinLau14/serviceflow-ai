package com.serviceflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.auth.login-limit=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRateLimitTest {
  @Autowired MockMvc mvc;

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
}
