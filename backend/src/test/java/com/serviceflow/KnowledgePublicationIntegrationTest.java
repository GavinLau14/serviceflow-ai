package com.serviceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.sql.Statement;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

/** HTTP fixtures verify the Java boundary, not real embedding or model quality. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class KnowledgePublicationIntegrationTest {
  static HttpServer server;
  static final ObjectMapper JSON=new ObjectMapper();
  static volatile boolean failIndex,stale;
  static volatile boolean failDelete;
  static volatile String blockedPath;
  static volatile CountDownLatch entered,released;
  static volatile JsonNode document;
  static {
    try {
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/",exchange->{
        byte[] bytes;
        try {
          JsonNode body=JSON.readTree(exchange.getRequestBody());String path=exchange.getRequestURI().getPath();
          if(path.equals(blockedPath)){entered.countDown();if(!released.await(20,TimeUnit.SECONDS))throw new IllegalStateException("fixture gate timed out");}
          Object response;
          if(path.equals("/publications/upsert")) {
            document=body.get("document");
            if(failIndex){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            response=Map.of("id",document.get("id").asText(),"version",document.get("version").asInt(),"indexed",1);
          } else if(path.equals("/publications/delete")){
            if(failDelete){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            response=Map.of("id",body.get("id").asText(),"deleted",true);
          }
          else {
            JsonNode docs=body.path("publications");JsonNode doc=stale?document:docs.isArray()&&!docs.isEmpty()?docs.get(0):null;
            if(doc==null)response=Map.of("mode","retrieval_only","intent","UNKNOWN","answer","没有可用知识，请转人工。","confidence",0,"handoff",true,"citations",List.of());
            else response=Map.of("mode","rag","intent","REPAIR","answer",doc.get("text").asText(),"confidence",.9,"handoff",false,
                "citations",List.of(Map.of("id",doc.get("id").asText(),"title",doc.get("title").asText(),"source",doc.get("source").asText(),"text",doc.get("text").asText(),"score",.9)),
                "proposal",Map.of("title",body.get("message").asText(),"description",body.get("message").asText(),"category","DEVICE","priority","NORMAL"));
          }
          bytes=JSON.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
      });server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));server.start();
    }catch(Exception e){throw new ExceptionInInitializerError(e);}
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("app.ai.enabled",()->true);r.add("app.ai.token",()->"test-publication-token");r.add("app.ai.url",()->"http://127.0.0.1:"+server.getAddress().getPort());}
  @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
  @BeforeEach void reset(){failIndex=false;failDelete=false;stale=false;document=null;blockedPath=null;entered=null;released=null;}
  JsonNode node(ResultActions r)throws Exception{return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));}
  String token(String user)throws Exception{return node(mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",user,"password","Test-Only-Password-123!")))).andExpect(status().isOk())).get("token").asText();}
  ResultActions postAs(String user,String path,Object body)throws Exception{return mvc.perform(post(path).header("Authorization","Bearer "+token(user)).contentType("application/json").content(json.writeValueAsString(body)));}
  ResultActions getAs(String user,String path)throws Exception{return mvc.perform(get(path).header("Authorization","Bearer "+token(user)));}
  long approved(){
    long ticket=insert("INSERT INTO tickets(reference,title,description,category,priority,status,customer_id,team_id,assignee_id) VALUES(?,'内部客户姓名与工单信息','不得发布的内部原文','DEVICE','NORMAL','CLOSED',1,1,3)","PUB-"+UUID.randomUUID());
    return insert("INSERT INTO knowledge_entries(ticket_id,team_id,title,content,status,author_id,reviewer_id) VALUES(?,1,'内部标题','包含内部信息的完整正文','PUBLISHED',3,5)",ticket);
  }
  long insert(String sql,Object arg){var key=new org.springframework.jdbc.support.GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);p.setObject(1,arg);return p;},key);return Objects.requireNonNull(key.getKey()).longValue();}
  Map<String,Object> publish(int version){return Map.of("expectedVersion",version,"title","公开排查方法","text","经过人工确认可向所有客户展示的通用排查摘要。","confirmed",true,"redacted",true);}
  JsonNode publish(long id)throws Exception{return node(postAs("admin","/api/knowledge/"+id+"/publication",publish(0)).andExpect(status().isOk()));}

  @Test void onlyAdminCanPublishReviewedSanitizedSnapshotsWithTwoConfirmations()throws Exception {
    long id=approved();String path="/api/knowledge/"+id+"/publication";
    postAs("supervisor",path,publish(0)).andExpect(status().isForbidden());
    postAs("agent",path,publish(0)).andExpect(status().isForbidden());
    var req=new HashMap<>(publish(0));req.put("redacted",false);postAs("admin",path,req).andExpect(status().isBadRequest());
    req.put("redacted",true);req.put("confirmed",false);postAs("admin",path,req).andExpect(status().isBadRequest());
    var p=publish(id);assertThat(p.get("indexState").asText()).isEqualTo("SYNCED");
    assertThat(document.toString()).doesNotContain("内部","ticketId","knowledgeId","authorId");
    getAs("otherlead",path).andExpect(status().isNotFound());
    var visible=node(getAs("alice","/api/knowledge/publications/"+p.get("id").asText()).andExpect(status().isOk()));
    mvc.perform(get("/api/knowledge/publications/"+p.get("id").asText())).andExpect(status().isUnauthorized());
    assertThat(visible.toString()).doesNotContain("knowledgeId","ticketId","publishedBy","内部");
    assertThat(visible.get("text").asText()).isEqualTo(publish(0).get("text"));
    db.update("UPDATE knowledge_entries SET status='DRAFT' WHERE id=?",id);
    postAs("admin",path,publish(1)).andExpect(status().isConflict());
  }

  @Test void failedIndexIsNotEligibleAndCanBeRetriedWithoutANewVersion()throws Exception {
    long id=approved();failIndex=true;var p=publish(id);String publicId=p.get("id").asText();
    assertThat(p.get("indexState").asText()).isEqualTo("FAILED");
    getAs("alice","/api/knowledge/publications/"+publicId).andExpect(status().isNotFound());
    var run=node(postAs("alice","/api/ai/assist",Map.of("message","电脑无法开机")).andExpect(status().isOk()));assertThat(run.get("citations").size()).isZero();
    failIndex=false;
    postAs("admin","/api/knowledge/"+id+"/publication/sync",Map.of("publicationId",publicId,"confirmed",true)).andExpect(status().isOk()).andExpect(jsonPath("indexState").value("SYNCED"));
    assertThat(node(postAs("admin","/api/knowledge/"+id+"/publication",publish(0)).andExpect(status().isOk())).get("id").asText()).isEqualTo(publicId);
  }

  @Test void withdrawalBlocksStaleAnswersHistorySourcesAndPendingToolExecution()throws Exception {
    long id=approved();var p=publish(id);String pid=p.get("id").asText();
    var run=node(postAs("alice","/api/ai/assist",Map.of("message","电脑无法开机")).andExpect(status().isOk()));assertThat(run.get("proposal").isNull()).isFalse();
    var action=Map.of("publicationId",pid,"confirmed",true);
    failDelete=true;
    postAs("admin","/api/knowledge/"+id+"/publication/withdraw",action).andExpect(status().isOk()).andExpect(jsonPath("status").value("WITHDRAWN")).andExpect(jsonPath("cleanupState").value("FAILED"));
    failDelete=false;
    postAs("admin","/api/knowledge/"+id+"/publication/withdraw",action).andExpect(status().isOk()).andExpect(jsonPath("cleanupState").value("DONE"));
    getAs("alice","/api/knowledge/publications/"+pid).andExpect(status().isNotFound());
    postAs("alice","/api/ai/runs/"+run.get("id").asText()+"/execute",Map.of("confirmed",true)).andExpect(status().isConflict());
    assertThat(node(getAs("alice","/api/ai/runs").andExpect(status().isOk())).toString()).doesNotContain("通用排查摘要");
    stale=true;
    var late=node(postAs("alice","/api/ai/assist",Map.of("message","电脑无法开机")).andExpect(status().isOk()));
    assertThat(late.get("citations").size()).isZero();assertThat(late.get("proposal").isNull()).isTrue();
    assertThat(late.get("answer").asText()).doesNotContain("通用排查摘要");
  }

  @Test void replacementCreatesImmutableVersionsAndRejectsStaleOperatorRequests()throws Exception {
    long id=approved();var first=publish(id);var req=new HashMap<>(publish(1));req.put("text","新版本的公开摘要，不包含原工单信息。");
    var second=node(postAs("admin","/api/knowledge/"+id+"/publication",req).andExpect(status().isOk()));
    assertThat(second.get("version").asInt()).isEqualTo(2);assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
    getAs("alice","/api/knowledge/publications/"+first.get("id").asText()).andExpect(status().isNotFound());
    postAs("admin","/api/knowledge/"+id+"/publication",publish(0)).andExpect(status().isConflict());
    postAs("admin","/api/knowledge/"+id+"/publication/withdraw",Map.of("publicationId",first.get("id").asText(),"confirmed",true)).andExpect(status().isOk());
    getAs("alice","/api/knowledge/publications/"+second.get("id").asText()).andExpect(status().isOk());
  }

  @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void withdrawalWinsAgainstInFlightIndexAndModelResponses()throws Exception {
    long id=approved();long ticket=db.queryForObject("SELECT ticket_id FROM knowledge_entries WHERE id=?",Long.class,id);
    String message="电脑无法开机-"+UUID.randomUUID();
    try(var pool=Executors.newSingleThreadExecutor()) {
      blockedPath="/publications/upsert";entered=new CountDownLatch(1);released=new CountDownLatch(1);
      var pending=pool.submit(()->publish(id));
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      String pid=db.queryForObject("SELECT id FROM knowledge_publications WHERE knowledge_id=?",String.class,id);
      postAs("admin","/api/knowledge/"+id+"/publication/withdraw",Map.of("publicationId",pid,"confirmed",true)).andExpect(status().isOk());
      released.countDown();assertThat(pending.get(10,TimeUnit.SECONDS).get("status").asText()).isEqualTo("WITHDRAWN");
      blockedPath=null;
      var second=node(postAs("admin","/api/knowledge/"+id+"/publication",publish(1)).andExpect(status().isOk()));
      blockedPath="/assist";entered=new CountDownLatch(1);released=new CountDownLatch(1);
      var answer=pool.submit(()->node(postAs("alice","/api/ai/assist",Map.of("message",message)).andExpect(status().isOk())));
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      postAs("admin","/api/knowledge/"+id+"/publication/withdraw",Map.of("publicationId",second.get("id").asText(),"confirmed",true)).andExpect(status().isOk());
      released.countDown();var run=answer.get(10,TimeUnit.SECONDS);
      assertThat(run.get("citations").size()).isZero();assertThat(run.get("proposal").isNull()).isTrue();
      assertThat(run.get("answer").asText()).doesNotContain("通用排查摘要");
    }finally{
      if(released!=null)released.countDown();blockedPath=null;
      db.update("DELETE FROM ai_runs WHERE original_message=?",message);
      db.update("DELETE FROM knowledge_publications WHERE knowledge_id=?",id);db.update("DELETE FROM knowledge_entries WHERE id=?",id);
      db.update("DELETE FROM ticket_comments WHERE ticket_id=?",ticket);db.update("DELETE FROM ticket_events WHERE ticket_id=?",ticket);db.update("DELETE FROM tickets WHERE id=?",ticket);
    }
  }
}
