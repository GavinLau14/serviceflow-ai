# ADR 0004 — Transactional collaboration and operations

Automatic assignment serializes by team before creating ticket FK rows, then uses current locking reads for agent capacity. Notifications use transactional outbox and unique outbox delivery identity, not external messaging. SLA uses injectable UTC Clock and bounded once-only escalation. Waiting for customer excludes resolution escalation; the original deadline is retained, not extended by accumulated waiting time.

Human handoff shares AI run row/ticket ID with confirmed tool execution, preserving concurrency idempotence. Classification is customer-selected, no keyless guessed routing. Public ticket thread is the durable human conversation, not a new realtime chat service.

Closed ticket knowledge requires manual content, explicit confirmation and scoped supervisor/admin review; published does not automatically enter Qdrant. Internal records never become customer notice bodies. Knowledge locks must flush MyBatis local query cache to obtain current state even within an outer transaction.

M3 changes MySQL via separate V3/V4 migrations. Existing source/credentials and ProcureGuard remain isolated. Staff audit is append-only in application routes, not tamper-proof against database administrators.
