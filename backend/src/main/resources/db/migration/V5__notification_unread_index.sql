CREATE INDEX idx_notice_owner_unread ON notifications(user_id,is_read,id);
