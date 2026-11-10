CREATE TABLE ticket_sla (
 ticket_id BIGINT PRIMARY KEY,
 first_response_due DATETIME(6) NOT NULL,
 resolution_due DATETIME(6) NOT NULL,
 first_responded_at DATETIME(6) NULL,
 escalated_at DATETIME(6) NULL,
 CONSTRAINT fk_sla_ticket FOREIGN KEY(ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
 INDEX idx_sla_response (escalated_at,first_response_due),
 INDEX idx_sla_resolution (escalated_at,resolution_due)
);
INSERT INTO ticket_sla(ticket_id,first_response_due,resolution_due)
 SELECT id,DATE_ADD(created_at,INTERVAL 1 HOUR),DATE_ADD(created_at,INTERVAL 24 HOUR) FROM tickets;
CREATE TABLE ticket_audit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 ticket_id BIGINT NOT NULL,
 actor_id BIGINT NULL,
 action VARCHAR(60) NOT NULL,
 message VARCHAR(2000) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_audit_ticket FOREIGN KEY(ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
 CONSTRAINT fk_audit_actor FOREIGN KEY(actor_id) REFERENCES users(id),
 INDEX idx_audit_ticket(ticket_id,id)
);
CREATE TABLE notification_outbox (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 audit_id BIGINT NOT NULL,
 ticket_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL,
 body VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 delivered_at DATETIME(6) NULL,
 CONSTRAINT fk_outbox_audit FOREIGN KEY(audit_id) REFERENCES ticket_audit(id) ON DELETE CASCADE,
 CONSTRAINT fk_outbox_ticket FOREIGN KEY(ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
 CONSTRAINT fk_outbox_user FOREIGN KEY(user_id) REFERENCES users(id),
 UNIQUE KEY uk_outbox_recipient(audit_id,user_id),
 INDEX idx_outbox_pending(delivered_at,id)
);
CREATE TABLE notifications (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 outbox_id BIGINT NOT NULL,
 ticket_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL,
 body VARCHAR(500) NOT NULL,
 is_read BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_notice_outbox FOREIGN KEY(outbox_id) REFERENCES notification_outbox(id) ON DELETE CASCADE,
 CONSTRAINT fk_notice_ticket FOREIGN KEY(ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
 CONSTRAINT fk_notice_user FOREIGN KEY(user_id) REFERENCES users(id),
 UNIQUE KEY uk_notice_outbox(outbox_id),
 INDEX idx_notice_owner(user_id,id)
);
