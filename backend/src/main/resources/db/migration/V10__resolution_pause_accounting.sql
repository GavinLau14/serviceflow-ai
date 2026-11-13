ALTER TABLE ticket_sla
  ADD COLUMN resolution_paused_at DATETIME(6) NULL,
  ADD COLUMN paused_micros BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT chk_sla_paused_micros CHECK (paused_micros >= 0);
-- Start recording existing waits now. Do not invent or backfill unmeasured historical duration.
UPDATE ticket_sla s JOIN tickets t ON t.id=s.ticket_id
SET s.resolution_paused_at=UTC_TIMESTAMP(6)
WHERE t.status='WAITING_CUSTOMER';
