ALTER TABLE knowledge_entries ADD COLUMN review_note VARCHAR(1000) NULL;
CREATE INDEX idx_knowledge_status_created ON knowledge_entries(status,created_at,id);
