ALTER TABLE ai_runs ADD COLUMN original_message TEXT NULL;
ALTER TABLE ai_runs ADD COLUMN handoff_requested BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE knowledge_entries (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 ticket_id BIGINT NOT NULL,
 team_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL,
 content TEXT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 author_id BIGINT NOT NULL,
 reviewer_id BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 reviewed_at DATETIME(6) NULL,
 active_draft_ticket BIGINT GENERATED ALWAYS AS (CASE WHEN status='DRAFT' THEN ticket_id ELSE NULL END) STORED,
 CONSTRAINT fk_knowledge_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
 CONSTRAINT fk_knowledge_team FOREIGN KEY (team_id) REFERENCES teams(id),
 CONSTRAINT fk_knowledge_author FOREIGN KEY (author_id) REFERENCES users(id),
 CONSTRAINT fk_knowledge_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id),
 CONSTRAINT chk_knowledge_status CHECK (status IN ('DRAFT','PUBLISHED','REJECTED')),
 UNIQUE KEY uk_knowledge_active_draft (active_draft_ticket),
 INDEX idx_knowledge_team (team_id,created_at,id)
);
CREATE TABLE knowledge_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 knowledge_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(40) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (knowledge_id) REFERENCES knowledge_entries(id) ON DELETE CASCADE,
 FOREIGN KEY (actor_id) REFERENCES users(id),
 INDEX idx_knowledge_event (knowledge_id,id)
);
