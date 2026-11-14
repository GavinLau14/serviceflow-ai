package com.serviceflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
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

  /** A bounded probe, separate from the longer inference timeout. No provider payload escapes. */
  public String health() throws Exception {
    if (!enabled()) throw new IllegalStateException("AI disabled");
    var request=HttpRequest.newBuilder(URI.create(url+"/health"))
        .timeout(Duration.ofSeconds(3)).header("X-Service-Token",token).GET().build();
    var future=http.sendAsync(request, info->new HealthBody());
    try {
      var response=future.get(3,TimeUnit.SECONDS);
      if(response.statusCode()!=200)throw new IllegalStateException("AI health unavailable");
      return new String(response.body(),StandardCharsets.UTF_8);
    } finally {
      if(!future.isDone())future.cancel(true);
    }
  }

  private static final class HealthBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody(){return result;}
    public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
    public void onNext(List<ByteBuffer> chunks){
      if(result.isDone()){subscription.cancel();return;}
      for(ByteBuffer chunk:chunks){
        if(chunk.remaining()>4096-bytes.size()){
          subscription.cancel();result.completeExceptionally(new IOException("AI health response too large"));return;
        }
        byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);
      }
      subscription.request(1);
    }
    public void onError(Throwable error){result.completeExceptionally(error);}
    public void onComplete(){result.complete(bytes.toByteArray());}
  }

  public AiModels.Output assist(String message) throws Exception {
    return json.readValue(call("/assist", Map.of("message", message)), AiModels.Output.class);
  }
  public AiModels.Output assist(String message,java.util.List<com.serviceflow.collaboration.PublicationService.Document> publications) throws Exception {
    return json.readValue(call("/assist",publications.isEmpty()?Map.of("message",message):Map.of("message",message,"publications",publications)),AiModels.Output.class);
  }
}
