CREATE TABLE knowledge_publication_guard(id TINYINT PRIMARY KEY);
INSERT INTO knowledge_publication_guard VALUES(1);
CREATE TABLE knowledge_publications (
 id CHAR(36) PRIMARY KEY,
 knowledge_id BIGINT NOT NULL,
 version INT NOT NULL,
 public_title VARCHAR(160) NOT NULL,
 public_text VARCHAR(1500) NOT NULL,
 status VARCHAR(20) NOT NULL,
 index_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 cleanup_state VARCHAR(20) NOT NULL DEFAULT 'NONE',
 published_by BIGINT NOT NULL,
 published_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 withdrawn_by BIGINT NULL,
 withdrawn_at DATETIME(6) NULL,
 active_knowledge_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN knowledge_id ELSE NULL END) STORED,
 FOREIGN KEY(knowledge_id) REFERENCES knowledge_entries(id),
 FOREIGN KEY(published_by) REFERENCES users(id),
 FOREIGN KEY(withdrawn_by) REFERENCES users(id),
 UNIQUE KEY uk_publication_version(knowledge_id,version),
 UNIQUE KEY uk_publication_active(active_knowledge_id),
 CONSTRAINT chk_publication_status CHECK(status IN ('ACTIVE','WITHDRAWN')),
 CONSTRAINT chk_publication_index CHECK(index_state IN ('PENDING','SYNCED','FAILED')),
 CONSTRAINT chk_publication_cleanup CHECK(cleanup_state IN ('NONE','PENDING','DONE','FAILED')),
 INDEX idx_publication_catalog(status,index_state,id)
);
