package com.serviceflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiGateway {
  private final boolean enabled;
  private final String url, token;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public AiGateway(
      @Value("${app.ai.enabled:false}") boolean enabled,
      @Value("${app.ai.url:http://127.0.0.1:18082}") String url,
      @Value("${app.ai.token:}") String token,
      ObjectMapper json) {
    this.enabled = enabled;
    this.url = url;
    this.token = token;
    this.json = json;
  }

  public boolean enabled() {
    return enabled && !token.isBlank();
  }

  public String call(String path, Object input) throws Exception {
    if (!enabled()) throw new IllegalStateException("AI disabled");
    var request =
        HttpRequest.newBuilder(URI.create(url + path))
            .timeout(Duration.ofSeconds(90))
            .header("Content-Type", "application/json")
            .header("X-Service-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200 || response.body().length() > 60000)
      throw new IllegalStateException("AI provider unavailable");
    return response.body();
  }

  public AiModels.Output assist(String message) throws Exception {
    return json.readValue(call("/assist", Map.of("message", message)), AiModels.Output.class);
  }
}
