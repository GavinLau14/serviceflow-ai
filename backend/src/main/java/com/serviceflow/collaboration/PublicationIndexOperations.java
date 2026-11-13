package com.serviceflow.collaboration;

import com.serviceflow.auth.User;
import com.serviceflow.common.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** A database snapshot of operations, not a claim of continuous Qdrant availability. */
@Service
public class PublicationIndexOperations {
  public record Summary(long active,long synced,long pending,long failed,long cleanupPending) {}
  public record Item(String id,long knowledgeId,int version,String title,String status,String indexState,
      String cleanupState,String checkState,LocalDateTime checkedAt,LocalDateTime publishedAt) {}
  public record Page(List<Item> items,long total,int page,int size,Summary summary) {}
  private final JdbcTemplate db;
  public PublicationIndexOperations(JdbcTemplate db){this.db=db;}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page page(User user,int page,int size,boolean problemsOnly){
    if(!user.is("ADMIN"))throw ApiException.forbidden();
    if(page<1||page>100000||size<1||size>50)throw ApiException.bad("页码需为1至100000，每页1至50条");
    Summary summary=db.queryForObject("SELECT COALESCE(SUM(status='ACTIVE'),0) active,COALESCE(SUM(status='ACTIVE' AND index_state='SYNCED'),0) synced,COALESCE(SUM(status='ACTIVE' AND index_state='PENDING'),0) pending,COALESCE(SUM(status='ACTIVE' AND index_state='FAILED'),0) failed,COALESCE(SUM(status='WITHDRAWN' AND cleanup_state IN ('PENDING','FAILED')),0) cleanup_pending FROM knowledge_publications",
        (r,n)->new Summary(r.getLong("active"),r.getLong("synced"),r.getLong("pending"),r.getLong("failed"),r.getLong("cleanup_pending")));
    String where=problemsOnly?" WHERE (status='ACTIVE' AND (index_state<>'SYNCED' OR check_state='UNAVAILABLE')) OR (status='WITHDRAWN' AND cleanup_state IN ('PENDING','FAILED'))":"";
    long total=db.queryForObject("SELECT COUNT(*) FROM knowledge_publications"+where,Long.class);
    List<Item> items=db.query("SELECT id,knowledge_id,version,public_title,status,index_state,cleanup_state,check_state,checked_at,published_at FROM knowledge_publications"+where+" ORDER BY published_at DESC,id DESC LIMIT ? OFFSET ?",
        (r,n)->new Item(r.getString("id"),r.getLong("knowledge_id"),r.getInt("version"),r.getString("public_title"),r.getString("status"),r.getString("index_state"),r.getString("cleanup_state"),r.getString("check_state"),r.getObject("checked_at",LocalDateTime.class),r.getObject("published_at",LocalDateTime.class)),size,(page-1)*size);
    return new Page(items,total,page,size,summary);
  }
}
