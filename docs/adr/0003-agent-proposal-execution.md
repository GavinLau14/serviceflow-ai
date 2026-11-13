# ADR 0003 — Evidence-gated proposals and Java execution

M2 uses LangGraph for retrieve → decide → validate. Python has only Qdrant/model configuration and an internal service token; no business database or customer bearer credentials. Internal endpoints use X-Service-Token and bind loopback by default.

Java authenticates the customer, logs a bounded response and prompt hash/latency, and persists any validated draft. Execute accepts only confirmed, locks an owner-scoped row, expires unexecuted drafts at 30 minutes, invokes existing TicketService and stores ticket ID in the same transaction. A retry returns that ticket. Client-provided tool arguments or owner IDs are rejected.

Model output is untrusted. Python validates tool name/schema, grounded citations and the original message; Java repeats shape/bounds checks and prevents draft execution unless mode=rag, confidence>=.55, evidence present and no handoff. Confidence is not a calibrated probability. Generated prose is advisory, never a source of ticket state or a substitute for actual policy.

Without LLM credentials, real embedding/Qdrant results are displayed as retrieval_only excerpts with no generative claims and no executable draft. Failed/unavailable providers produce explicit handoff and no action. Demo knowledge is marked fictional; no refund/policy promises are deployed.

Model artifacts are downloads, not vendored source; lock dependencies, retain license details, exclude cache and secrets. Tests with deterministic provider responses prove contracts only, not live LLM quality.

## v0.9 客户端迟到响应

后端确认成功的run→ticket关联在当前组件内保持单调（最多50条，不缓存咨询正文）。合并完成状态时采用最新current/history安全字段；最新历史遗漏该run则使用无引用、无提案提示，不恢复捕获的旧建议。来源预览回执核对请求代次、runId和仍有效的当前引用。门闩测试覆盖建单先完成/历史后到，以及历史先遮蔽/建单或来源预览后到。服务端幂等与最终权限不依赖客户端缓存。
