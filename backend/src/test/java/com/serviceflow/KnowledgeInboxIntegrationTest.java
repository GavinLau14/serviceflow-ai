package com.serviceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class KnowledgeInboxIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  String token(String username) throws Exception {
    return node(mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(Map.of("username",username,"password","Test-Only-Password-123!"))))
        .andExpect(status().isOk())).get("token").asText();
  }
  JsonNode node(ResultActions r) throws Exception {
    return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }
  ResultActions inbox(String user,int page,int size,String status,String q) throws Exception {
    return mvc.perform(get("/api/knowledge/inbox").header("Authorization","Bearer "+token(user))
        .param("page",String.valueOf(page)).param("size",String.valueOf(size)).param("status",status).param("q",q));
  }
  ResultActions review(String user,long id,Map<String,Object> body) throws Exception {
    return mvc.perform(post("/api/knowledge/"+id+"/review").header("Authorization","Bearer "+token(user))
        .contentType("application/json").content(json.writeValueAsString(body)));
  }
  long seed(long team,Long assignee,String status,String title,String content) {
    db.update("INSERT INTO tickets(reference,title,description,category,priority,status,customer_id,team_id,assignee_id) VALUES(?,?,?,?,'NORMAL','CLOSED',1,?,?)",
        "KB-"+UUID.randomUUID(),"来源工单","fixture",team==1?"DEVICE":"APPLIANCE",team,assignee);
    long ticket=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    db.update("INSERT INTO knowledge_entries(ticket_id,team_id,title,content,status,author_id,created_at) VALUES(?,?,?,?,?,3,'2030-01-01 00:00:00')",ticket,team,title,content,status);
    return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
  }

  @Test void reachesHistoryBeyond100AndReturnsSourceMetadata() throws Exception {
    for(int i=0;i<108;i++)seed(1,3L,i%3==0?"DRAFT":"PUBLISHED","知识"+i,"人工整理的演示内容");
    var first=node(inbox("supervisor",1,20,"","").andExpect(status().isOk()));
    var last=node(inbox("supervisor",6,20,"","").andExpect(status().isOk()));
    assertThat(first.get("total").asInt()).isEqualTo(108);
    assertThat(first.get("pendingTotal").asInt()).isEqualTo(36);
    assertThat(first.get("items").size()).isEqualTo(20);
    assertThat(last.get("items").size()).isEqualTo(8);
    assertThat(first.get("items").get(0).get("authorName").asText()).isNotBlank();
    assertThat(first.get("items").get(0).get("ticketTitle").asText()).isEqualTo("来源工单");
    assertThat(first.get("items").get(19).get("id").asLong()).isGreaterThan(last.get("items").get(0).get("id").asLong());
  }

  @Test void allCountsAndRowsFollowCurrentTicketScope() throws Exception {
    seed(1,3L,"DRAFT","数码知识","内容");seed(1,null,"DRAFT","未受派知识","内容");
    seed(2,4L,"PUBLISHED","家电知识","内容");
    inbox("agent",1,20,"","").andExpect(status().isOk()).andExpect(jsonPath("total").value(1)).andExpect(jsonPath("pendingTotal").value(1));
    inbox("supervisor",1,20,"","").andExpect(status().isOk()).andExpect(jsonPath("total").value(2)).andExpect(jsonPath("pendingTotal").value(2));
    inbox("otherlead",1,20,"","").andExpect(status().isOk()).andExpect(jsonPath("total").value(1)).andExpect(jsonPath("pendingTotal").value(0));
    inbox("admin",1,20,"","").andExpect(status().isOk()).andExpect(jsonPath("total").value(3)).andExpect(jsonPath("pendingTotal").value(2));
    inbox("alice",1,20,"","").andExpect(status().isForbidden());
  }

  @Test void literalSearchStatusAndGlobalPendingCountStayConsistent() throws Exception {
    seed(1,3L,"DRAFT","电源_100%","检查接头");seed(1,3L,"PUBLISHED","电源abc1000","连接线\\测试");
    inbox("supervisor",1,20,"DRAFT","%_").andExpect(status().isOk()).andExpect(jsonPath("total").value(0));
    for(String q:List.of("%","_","接头"))
      inbox("supervisor",1,20,"DRAFT",q).andExpect(status().isOk()).andExpect(jsonPath("total").value(1));
    inbox("supervisor",1,20,"PUBLISHED","\\").andExpect(status().isOk()).andExpect(jsonPath("total").value(1)).andExpect(jsonPath("pendingTotal").value(1));
    inbox("supervisor",1,20,"","%' OR 1=1 --").andExpect(status().isOk()).andExpect(jsonPath("total").value(0));
    inbox("supervisor",Integer.MAX_VALUE,100,"","").andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(0)).andExpect(jsonPath("total").value(2));
  }

  @Test void rejectionReasonIsRequiredScopedAndImmutable() throws Exception {
    long id=seed(1,3L,"DRAFT","待修正知识","人工内容");
    review("supervisor",id,Map.of("approved",false,"confirmed",true)).andExpect(status().isBadRequest());
    review("supervisor",id,Map.of("approved",false,"confirmed",true,"note"," ")).andExpect(status().isBadRequest());
    var request=Map.<String,Object>of("approved",false,"confirmed",true,"note","  请补充复测结果，删除客户联系方式。  ");
    review("otherlead",id,request).andExpect(status().isNotFound());
    review("agent",id,request).andExpect(status().isForbidden());
    var rejected=node(review("supervisor",id,request).andExpect(status().isOk()));
    assertThat(rejected.get("reviewNote").asText()).isEqualTo("请补充复测结果，删除客户联系方式。");
    review("admin",id,request).andExpect(status().isOk()).andExpect(jsonPath("reviewerId").value(5));
    review("supervisor",id,Map.of("approved",false,"confirmed",true,"note","改掉原理由")).andExpect(status().isConflict());
    review("supervisor",id,Map.of("approved",true,"confirmed",true)).andExpect(status().isConflict());
    inbox("agent",1,20,"REJECTED","").andExpect(status().isOk()).andExpect(jsonPath("items[0].reviewNote").value("请补充复测结果，删除客户联系方式。"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_events WHERE knowledge_id=?",Integer.class,id)).isEqualTo(1);
  }

  @Test void approvalNoteIsOptionalAndLegacyReviewsAreNotRewritten() throws Exception {
    long approved=seed(1,3L,"DRAFT","可以通过","内容");
    review("supervisor",approved,Map.of("approved",true,"confirmed",true)).andExpect(status().isOk());
    review("supervisor",approved,Map.of("approved",true,"confirmed",true,"note","追加说明")).andExpect(status().isConflict());
    long legacy=seed(1,3L,"REJECTED","历史退回","内容");
    review("supervisor",legacy,Map.of("approved",false,"confirmed",true)).andExpect(status().isOk()).andExpect(jsonPath("reviewNote").isEmpty());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_events WHERE knowledge_id=?",Integer.class,legacy)).isZero();
  }

  @Test void rejectsInvalidQueriesAndOversizedNotes() throws Exception {
    inbox("supervisor",0,20,"","").andExpect(status().isBadRequest());
    inbox("supervisor",1,101,"","").andExpect(status().isBadRequest());
    inbox("supervisor",1,20,"published","").andExpect(status().isBadRequest());
    inbox("supervisor",1,20,"","x".repeat(101)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/knowledge/inbox")).andExpect(status().isUnauthorized());
    long id=seed(1,3L,"DRAFT","输入边界","内容");
    review("supervisor",id,Map.of("approved",true,"confirmed",true,"note","x".repeat(1001))).andExpect(status().isBadRequest());
  }
}
