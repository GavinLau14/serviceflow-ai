package com.serviceflow.collaboration;

import com.serviceflow.ai.*;
import com.serviceflow.auth.User;
import com.serviceflow.common.ApiException;
import com.serviceflow.operations.OperationsService;
import com.fasterxml.jackson.databind.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL is the authorization source. Vector state never grants citation authority. */
@Service
public class PublicationService {
  public record Publication(String id,long knowledgeId,int version,String title,String text,String status,
      String indexState,String cleanupState,long publishedBy,LocalDateTime publishedAt,Long withdrawnBy,LocalDateTime withdrawnAt) {}
  public record Document(String id,int version,String title,String source,String text) {}
  public record History(Publication latest,List<Publication> history) {}
  private record Authorized(Publication publication,String replacedId) {}
  private final JdbcTemplate db;private final KnowledgeMapper knowledge;private final OperationsService operations;
  private final AiGateway gateway;private final ObjectMapper json;private final TransactionTemplate tx;
  private static final RowMapper<Publication> ROW=(r,n)->new Publication(r.getString("id"),r.getLong("knowledge_id"),r.getInt("version"),r.getString("public_title"),r.getString("public_text"),r.getString("status"),r.getString("index_state"),r.getString("cleanup_state"),r.getLong("published_by"),r.getObject("published_at",LocalDateTime.class),r.getObject("withdrawn_by",Long.class),r.getObject("withdrawn_at",LocalDateTime.class));
  public PublicationService(JdbcTemplate db,KnowledgeMapper knowledge,OperationsService operations,AiGateway gateway,ObjectMapper json,PlatformTransactionManager manager){
    this.db=db;this.knowledge=knowledge;this.operations=operations;this.gateway=gateway;this.json=json;
    tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
  private void admin(User u){if(!u.is("ADMIN"))throw ApiException.forbidden();}
  private Knowledge visible(User u,long id){if(u.is("CUSTOMER"))throw ApiException.forbidden();Knowledge k=knowledge.find(id,u);if(k==null)throw ApiException.missing();return k;}
  private Publication row(String id,boolean lock){var rows=db.query("SELECT * FROM knowledge_publications WHERE id=?"+(lock?" FOR SHARE":""),ROW,id);return rows.isEmpty()?null:rows.getFirst();}
  private List<Publication> versions(long id){return db.query("SELECT * FROM knowledge_publications WHERE knowledge_id=? ORDER BY version DESC LIMIT 20",ROW,id);}
  private Document document(Publication p){return new Document("pub:"+p.id(),p.version(),p.title(),"knowledge://serviceflow/publications/"+p.id()+"/v"+p.version(),p.text());}
  private boolean eligible(Publication p){return p!=null&&p.status().equals("ACTIVE")&&p.indexState().equals("SYNCED");}
  public History history(User u,long id){visible(u,id);var list=versions(id);return new History(list.isEmpty()?null:list.getFirst(),list);}
  public Document publicSource(String id){Publication p=row(id,false);if(!eligible(p))throw ApiException.missing();return document(p);}
  public List<Document> catalog(){var list=db.query("SELECT * FROM knowledge_publications WHERE status='ACTIVE' AND index_state='SYNCED' ORDER BY id LIMIT 51",ROW);if(list.size()>50)throw new IllegalStateException("Publication catalog capacity exceeded");return list.stream().map(this::document).toList();}

  public Publication publish(User u,long id,PublicationController.Publish req){
    admin(u);if(!req.confirmed()||!req.redacted())throw ApiException.bad("请确认已脱敏并授权面向全部客户发布");
    Authorized result=tx.execute(s->{
      db.queryForObject("SELECT id FROM knowledge_publication_guard WHERE id=1 FOR UPDATE",Integer.class);
      Knowledge k=visible(u,id);knowledge.lockTicket(k.ticketId);k=knowledge.lock(id,u);
      if(k==null||!"PUBLISHED".equals(k.status))throw new ApiException(409,"KNOWLEDGE_NOT_APPROVED","仅审核通过的知识可以发布");
      var list=versions(id);Publication latest=list.isEmpty()?null:list.getFirst();int version=latest==null?0:latest.version();
      String title=req.title().strip(),text=req.text().strip();
      if(latest!=null&&latest.status().equals("ACTIVE")&&latest.title().equals(title)&&latest.text().equals(text)
          &&(req.expectedVersion()==version||req.expectedVersion()==version-1))return new Authorized(latest,null);
      if(req.expectedVersion()!=version)throw new ApiException(409,"PUBLICATION_CHANGED","发布版本已变化，请刷新后重试");
      boolean replacing=latest!=null&&latest.status().equals("ACTIVE");
      if(!replacing&&db.queryForObject("SELECT COUNT(*) FROM knowledge_publications WHERE status='ACTIVE'",Long.class)>=50)
        throw new ApiException(409,"PUBLICATION_CAPACITY","当前演示最多允许50条有效公开知识");
      if(replacing)markWithdrawn(latest.id(),u.id);
      String uuid=UUID.randomUUID().toString();
      db.update("INSERT INTO knowledge_publications(id,knowledge_id,version,public_title,public_text,status,published_by) VALUES(?,?,?,?,?,'ACTIVE',?)",uuid,id,Math.addExact(version,1),title,text,u.id);
      knowledge.event(id,u.id,"PUBLICATION_AUTHORIZED");operations.recordStaff(knowledge.lockTicket(k.ticketId),u.id,"KNOWLEDGE_PUBLICATION","已显式授权公开脱敏摘要，索引结果另行确认");
      return new Authorized(row(uuid,false),replacing?latest.id():null);
    });
    if(result.replacedId()!=null)cleanup(result.replacedId());
    return index(result.publication().id());
  }
  public Publication retry(User u,long id,PublicationController.Action req){
    admin(u);if(!req.confirmed())throw ApiException.bad("请确认重试索引");visible(u,id);
    Publication p=row(req.publicationId(),false);if(p==null||p.knowledgeId()!=id)throw ApiException.missing();
    if(!p.status().equals("ACTIVE"))throw new ApiException(409,"PUBLICATION_WITHDRAWN","已撤回的版本不可重新索引");return index(p.id());
  }
  private void markWithdrawn(String id,long actor){db.update("UPDATE knowledge_publications SET status='WITHDRAWN',cleanup_state='PENDING',withdrawn_by=?,withdrawn_at=UTC_TIMESTAMP(6) WHERE id=? AND status='ACTIVE'",actor,id);}
  public Publication withdraw(User u,long id,PublicationController.Action req){
    admin(u);if(!req.confirmed())throw ApiException.bad("请确认撤回该公开版本");
    tx.executeWithoutResult(s->{
      Knowledge k=visible(u,id);knowledge.lockTicket(k.ticketId);knowledge.lock(id,u);
      Publication p=row(req.publicationId(),false);if(p==null||p.knowledgeId()!=id)throw ApiException.missing();
      if(p.status().equals("ACTIVE")){markWithdrawn(p.id(),u.id);knowledge.event(id,u.id,"PUBLICATION_WITHDRAWN");operations.recordStaff(knowledge.lockTicket(k.ticketId),u.id,"KNOWLEDGE_WITHDRAWN","已撤回公开知识，后续引用和旧提案执行将被禁止");}
    });
    cleanup(req.publicationId());return row(req.publicationId(),false);
  }
  private Publication index(String id){
    Publication p=row(id,false);if(p==null||!p.status().equals("ACTIVE")||p.indexState().equals("SYNCED"))return p;
    boolean ok=false;
    try{JsonNode ack=json.readTree(gateway.call("/publications/upsert",Map.of("document",document(p))));
      ok=ack.path("id").asText().equals("pub:"+id)&&ack.path("version").isIntegralNumber()&&ack.path("version").asInt()==p.version()&&ack.path("indexed").isIntegralNumber()&&ack.path("indexed").asInt()==1;
    }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
    boolean synced=ok;
    int changed=tx.execute(s->db.update("UPDATE knowledge_publications SET index_state=? WHERE id=? AND status='ACTIVE'"+(synced?"":" AND index_state<>'SYNCED'"),synced?"SYNCED":"FAILED",id));
    if(changed==0)cleanup(id);return row(id,false);
  }
  private void cleanup(String id){
    Publication p=row(id,false);if(p==null||!p.status().equals("WITHDRAWN"))return;
    boolean ok=false;
    try{JsonNode ack=json.readTree(gateway.call("/publications/delete",Map.of("id","pub:"+id)));ok=ack.path("id").asText().equals("pub:"+id)&&ack.path("deleted").isBoolean()&&ack.path("deleted").asBoolean();}
    catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
    boolean deleted=ok;tx.executeWithoutResult(s->db.update("UPDATE knowledge_publications SET cleanup_state=? WHERE id=? AND status='WITHDRAWN'",deleted?"DONE":"FAILED",id));
  }

  public boolean referencesCurrent(List<AiModels.Citation> citations,boolean lock){
    if(citations==null)return false;
    if(citations.stream().anyMatch(c->c==null||c.id()==null||c.source()==null))return false;
    var ordered=citations.stream().filter(Objects::nonNull).sorted(Comparator.comparing(AiModels.Citation::id)).toList();
    for(var c:ordered){
      boolean dynamic=c.id().startsWith("pub:")||c.source().startsWith("knowledge://");if(!dynamic)continue;
      if(!c.id().startsWith("pub:"))return false;
      Publication p=row(c.id().substring(4),lock);if(!eligible(p))return false;
      Document d=document(p);if(!d.id().equals(c.id())||!d.title().equals(c.title())||!d.source().equals(c.source())||!d.text().equals(c.text()))return false;
    }return true;
  }
  public AiModels.Output safe(AiModels.Output output,boolean lock){
    if(referencesCurrent(output.citations(),lock))return output;
    return new AiModels.Output("error","UNKNOWN","知识来源已更新或撤回，请重新咨询或转人工处理。",0,true,List.of(),null);
  }
  public void requireCurrent(AiModels.Output output){if(!referencesCurrent(output.citations(),true))throw new ApiException(409,"KNOWLEDGE_WITHDRAWN","引用知识已撤回，请重新咨询或转人工");}
}
