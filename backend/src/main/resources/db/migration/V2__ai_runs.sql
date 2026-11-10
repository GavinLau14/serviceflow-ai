CREATE TABLE ai_runs (
 id VARCHAR(36) PRIMARY KEY,
 customer_id BIGINT NOT NULL,
 response_json LONGTEXT NOT NULL,
 message_hash VARCHAR(64) NOT NULL,
 latency_ms BIGINT NOT NULL,
 ticket_id BIGINT NULL UNIQUE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(customer_id) REFERENCES users(id),
 FOREIGN KEY(ticket_id) REFERENCES tickets(id),
 INDEX idx_ai_owner (customer_id,created_at,id)
);
