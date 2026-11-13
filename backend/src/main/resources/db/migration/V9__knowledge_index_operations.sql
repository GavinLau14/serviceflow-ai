ALTER TABLE knowledge_publications
  ADD COLUMN operation_revision BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN check_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN checked_at DATETIME(6) NULL,
  ADD CONSTRAINT chk_publication_check_state CHECK (check_state IN ('UNKNOWN','CURRENT','MISSING','INVALID','UNAVAILABLE'));
