CREATE TABLE teams (
 id BIGINT PRIMARY KEY,
 name VARCHAR(80) NOT NULL
);
CREATE TABLE users (
 id BIGINT PRIMARY KEY,
 username VARCHAR(50) NOT NULL UNIQUE,
 display_name VARCHAR(80) NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(20) NOT NULL,
 team_id BIGINT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 CONSTRAINT fk_user_team FOREIGN KEY (team_id) REFERENCES teams(id),
 CONSTRAINT chk_user_role CHECK (role IN ('CUSTOMER','AGENT','SUPERVISOR','ADMIN'))
);
INSERT INTO teams VALUES (1,'数码设备服务组'),(2,'家电服务组');
CREATE TABLE tickets (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 reference VARCHAR(40) NOT NULL UNIQUE,
 title VARCHAR(160) NOT NULL,
 description TEXT NOT NULL,
 category VARCHAR(30) NOT NULL,
 priority VARCHAR(20) NOT NULL,
 status VARCHAR(30) NOT NULL,
 customer_id BIGINT NOT NULL,
 team_id BIGINT NOT NULL,
 assignee_id BIGINT NULL,
 version INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_ticket_customer FOREIGN KEY (customer_id) REFERENCES users(id),
 CONSTRAINT fk_ticket_team FOREIGN KEY (team_id) REFERENCES teams(id),
 CONSTRAINT fk_ticket_assignee FOREIGN KEY (assignee_id) REFERENCES users(id),
 CONSTRAINT chk_ticket_status CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','WAITING_CUSTOMER','CLOSED')),
 CONSTRAINT chk_ticket_priority CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
 CONSTRAINT chk_ticket_category CHECK (category IN ('DEVICE','APPLIANCE')),
 INDEX idx_ticket_customer (customer_id,status,created_at,id),
 INDEX idx_ticket_assignee (assignee_id,status,created_at,id),
 INDEX idx_ticket_team (team_id,status,created_at,id)
);
CREATE TABLE ticket_comments (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 ticket_id BIGINT NOT NULL,
 author_id BIGINT NOT NULL,
 body TEXT NOT NULL,
 internal BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (ticket_id) REFERENCES tickets(id),
 FOREIGN KEY (author_id) REFERENCES users(id),
 INDEX idx_comment_ticket (ticket_id,id)
);
CREATE TABLE ticket_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 ticket_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(40) NOT NULL,
 from_status VARCHAR(30),
 to_status VARCHAR(30) NOT NULL,
 message VARCHAR(2000) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (ticket_id) REFERENCES tickets(id),
 FOREIGN KEY (actor_id) REFERENCES users(id),
 INDEX idx_event_ticket (ticket_id,id)
);
