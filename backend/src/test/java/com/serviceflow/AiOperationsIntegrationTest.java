package com.serviceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

/** Synthetic HTTP/log fixtures verify observability boundaries, not real model quality. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AiOperationsIntegrationTest {
  static HttpServer server;static volatile String behavior="OK";
  static {
    try{
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/health",e->{
        String current=behavior;try{
          if(current.equals("SLOW"))Thread.sleep(10000);
          if(current.equals("SLOW_BODY")){e.sendResponseHeaders(200,0);e.getResponseBody().write(32);e.getResponseBody().flush();Thread.sleep(10000);e.close();return;}
          String body=current.equals("BAD")?"{\"mode\":\"rag\",\"retrieval_ready\":true,\"llm_configured\":false}":current.equals("LEAK")?"private-upstream-key-must-not-escape":"{\"mode\":\"retrieval_only\",\"retrieval_ready\":true,\"llm_configured\":false}";
          if(current.equals("OVERSIZE"))body="{\"mode\":\"retrieval_only\",\"retrieval_ready\":true,\"llm_configured\":false,\"padding\":\""+"x".repeat(5000)+"\"}";
          byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(current.equals("LEAK")?503:200,bytes.length);e.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{e.close();}
      });server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));server.start();
    }catch(Exception e){throw new ExceptionInInitializerError(e);}
  }
  @DynamicPropertySource static void settings(DynamicPropertyRegistry r){r.add("app.ai.enabled",()->true);r.add("app.ai.token",()->"test-ops-service-token");r.add("app.ai.url",()->"http://127.0.0.1:"+server.getAddress().getPort());}
  @TestConfiguration static class Config {@Bean @Primary Clock opsClock(){return Clock.fixed(Instant.parse("2030-01-02T00:00:00Z"),ZoneOffset.UTC);}}
  @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
  @BeforeEach void reset(){behavior="OK";}
  JsonNode node(ResultActions r)throws Exception{return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
  String token(String user)throws Exception{return node(mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",user,"password","Test-Only-Password-123!")))).andExpect(status().isOk())).get("token").asText();}
  ResultActions getAs(String user,String path)throws Exception{return mvc.perform(get(path).header("Authorization","Bearer "+token(user)));}
  String insert(String body,int millis,Long ticket,String time){String id=UUID.randomUUID().toString();db.update("INSERT INTO ai_runs(id,customer_id,response_json,message_hash,latency_ms,ticket_id,created_at,original_message,handoff_requested) VALUES(?,1,?,'private-hash-not-for-ops',?,?,?,'private-customer-input-not-for-ops',?)",id,body,millis,ticket,time,ticket!=null);return id;}
  String output(String mode,boolean proposal)throws Exception{var data=new HashMap<String,Object>(Map.of("mode",mode,"answer","private-answer-not-for-ops","intent","private-intent-not-for-ops","confidence",.9,"handoff",false,"citations",List.of(Map.of("id","private-source-id","text","private-quoted-text"))));data.put("proposal",proposal?Map.of("title","private-proposal-title"):null);return json.writeValueAsString(data);}

  @Test void disabledGatewayDoesNotProbeAndKeepsUnknownFlagsNull(){
    var gateway=new com.serviceflow.ai.AiGateway(false,"http://127.0.0.1:1","",json);
    var service=new com.serviceflow.ai.AiOperationsService(db,gateway,json,Clock.fixed(Instant.parse("2030-01-02T00:00:00Z"),ZoneOffset.UTC));
    var admin=new com.serviceflow.auth.User();admin.role="ADMIN";
    var health=service.health(admin);assertThat(health.status()).isEqualTo("NOT_CONFIGURED");assertThat(health.retrievalReady()).isNull();assertThat(health.llmConfigured()).isNull();
  }

  @Test void allOperationsAreAdminOnly()throws Exception{
    for(String user:List.of("alice","agent","supervisor","otherlead")){getAs(user,"/api/operations/ai-runs").andExpect(status().isForbidden());getAs(user,"/api/operations/ai-health").andExpect(status().isForbidden());}
    mvc.perform(get("/api/operations/ai-runs")).andExpect(status().isUnauthorized());
  }

  @Test void windowsFiltersPaginationAndCorruptLogsNeverExposeCustomerPayload()throws Exception{
    var key=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement("INSERT INTO tickets(reference,title,description,category,priority,status,customer_id,team_id) VALUES(?,'private-ticket-title','private-ticket-body','DEVICE','NORMAL','OPEN',1,1)",java.sql.Statement.RETURN_GENERATED_KEYS);p.setString(1,"OPS-"+UUID.randomUUID().toString().substring(0,8));return p;},key);long ticket=key.getKey().longValue();
    String first=insert(output("rag",true),100,ticket,"2030-01-02 00:00:00");insert(output("retrieval_only",false),200,null,"2030-01-01 23:59:00");insert(output("error",false),300,null,"2030-01-01 23:58:00");insert(output("disabled",false),400,null,"2030-01-01 23:57:00");insert("broken-private-json",-1,null,"2030-01-01 23:56:00");insert(output("private-unknown-mode",false),600,null,"2030-01-01 23:55:00");insert(output("retrieval_only",false),500,null,"2030-01-01 00:00:00");insert(output("rag",false),700,null,"2029-12-31 23:59:59.999999");insert(output("rag",false),800,null,"2030-01-02 00:00:00.000001");
    var all=node(getAs("admin","/api/operations/ai-runs?size=2").andExpect(status().isOk()));assertThat(all.get("total").asInt()).isEqualTo(7);assertThat(all.get("items").size()).isEqualTo(2);assertThat(all.get("items").get(0).get("id").asText()).isEqualTo(first);assertThat(all.get("asOf").asText()).isEqualTo("2030-01-02T00:00:00");assertThat(all.get("summary").get("averageLatencyMs").asInt()).isEqualTo(350);assertThat(all.get("summary").get("unknown").asInt()).isEqualTo(2);
    var rag=node(getAs("admin","/api/operations/ai-runs?mode=rag&linkedOnly=true").andExpect(status().isOk()));assertThat(rag.get("total").asInt()).isEqualTo(1);assertThat(rag.get("summary")).isEqualTo(all.get("summary"));assertThat(rag.get("items").get(0).get("ticketId").asLong()).isEqualTo(ticket);
    var unknown=node(getAs("admin","/api/operations/ai-runs?mode=unknown").andExpect(status().isOk()));assertThat(unknown.get("total").asInt()).isEqualTo(2);assertThat(unknown.toString()).doesNotContain("private-","broken-","customerId","responseJson","messageHash","originalMessage");
    var second=node(getAs("admin","/api/operations/ai-runs?size=2&page=2").andExpect(status().isOk()));assertThat(second.get("items").get(0).get("id").asText()).isNotEqualTo(first);
    assertThat(all.toString()+rag.toString()+second.toString()).doesNotContain("private-","customerId","responseJson","originalMessage","messageHash");
  }

  @Test void onlyExactCanonicalModesCanLeaveTheMetadataBoundary()throws Exception{
    insert(output("rag ",false),100,null,"2030-01-02 00:00:00");insert(output("RAG",false),100,null,"2030-01-02 00:00:00");
    var result=node(getAs("admin","/api/operations/ai-runs?mode=unknown").andExpect(status().isOk()));
    assertThat(result.get("total").asInt()).isEqualTo(2);for(var row:result.get("items"))assertThat(row.get("mode").asText()).isEqualTo("unknown");
  }

  @Test void invalidWindowsAndFiltersAreRejected()throws Exception{
    for(String query:List.of("hours=0","hours=169","page=0","size=101","mode=private-unknown-mode"))getAs("admin","/api/operations/ai-runs?"+query).andExpect(status().isBadRequest());
    var empty=node(getAs("admin","/api/operations/ai-runs?hours=1").andExpect(status().isOk()));assertThat(empty.get("summary").get("averageLatencyMs").isNull()).isTrue();
  }

  @Test void healthReportsReachabilityWithoutPretendingTheModelWasTested()throws Exception{
    var result=node(getAs("admin","/api/operations/ai-health").andExpect(status().isOk()));assertThat(result.get("status").asText()).isEqualTo("REACHABLE");assertThat(result.get("mode").asText()).isEqualTo("retrieval_only");assertThat(result.get("llmConfigured").asBoolean()).isFalse();assertThat(result.get("retrievalReady").asBoolean()).isTrue();assertThat(result.toString()).doesNotContain("token","http://","api_key");
    behavior="BAD";getAs("admin","/api/operations/ai-health").andExpect(status().isOk()).andExpect(jsonPath("status").value("UNAVAILABLE")).andExpect(jsonPath("llmConfigured").isEmpty());
    behavior="LEAK";assertThat(node(getAs("admin","/api/operations/ai-health").andExpect(status().isOk())).toString()).doesNotContain("private-upstream");
  }

  @Test void oversizedHealthAndSlowBodyAreBoundedAndSanitized()throws Exception{
    behavior="OVERSIZE";getAs("admin","/api/operations/ai-health").andExpect(status().isOk()).andExpect(jsonPath("status").value("UNAVAILABLE"));
    behavior="SLOW_BODY";long started=System.nanoTime();getAs("admin","/api/operations/ai-health").andExpect(status().isOk()).andExpect(jsonPath("status").value("UNAVAILABLE"));assertThat((System.nanoTime()-started)/1_000_000).isLessThan(8000);
  }

  @Test void healthTimeoutIsBoundedAndDoesNotExposeExceptionText()throws Exception{
    behavior="SLOW";long started=System.nanoTime();var result=node(getAs("admin","/api/operations/ai-health").andExpect(status().isOk()));assertThat((System.nanoTime()-started)/1_000_000).isLessThan(8000);assertThat(result.get("status").asText()).isEqualTo("UNAVAILABLE");assertThat(result.toString()).doesNotContain("Exception","timed out","http://");
  }
}
