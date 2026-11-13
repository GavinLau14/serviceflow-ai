# M2 API 与模式约定

所有客户接口先经过 M1 bearer 身份验证。`POST /api/ai/assist {message}` 仅客户可用，message 1–5000 非空白字符；生成一条持久化调用记录。`GET /api/ai/runs` 返回本人最近20次记录。prompt 只保存 SHA-256，日志包含响应、模式、耗时和可选已创建工单 ID；草稿描述仍包含客户原始问题，仅本人可查，不能当作已脱敏数据。

响应：`{id,mode,intent,answer,confidence,handoff,citations:[{id,title,source,text,score}],proposal:null|{title,description,category,priority},status,ticketId}`。

- rag：真实 LLM adapter 已返回且证据与结构校验通过。answer 受保守引用约束，不能代表真实业务状态。
- retrieval_only：真实向量检索的原文摘录，未执行 LLM 生成，不可执行提案。
- disabled/error：配置/服务/校验不可用，明确建议人工处理，不产生业务副作用。
- confidence 为内部未校准评分，不能解释为正确率。
- status PENDING / EXPIRED / COMPLETED / NO_ACTION。未执行提案30分钟过期。

`POST /api/ai/runs/{id}/execute {confirmed:true}` 只接受显式确认，不能传 tool arguments、customerId 或任意 SQL。Java 从持久化记录取提案，按本人和行锁校验后调用 TicketService，与 ticketId 在同一事务提交；重复/并发请求返回同一工单。过期409、非本人404、无提案/未确认400。

`POST /api/ai/index {}` 仅管理员。代理 Python 固定的演示知识索引，不接受任意文件路径或网络抓取 URL。M2 只提供固定演示知识，不是生产售后政策库或一般文档管理系统。

`GET /api/capabilities` 的 enabled/ragEnabled 表示 Java 配置已打开，不保证模型就绪；实际结果以 mode 与 Python /health 为准。

Python：仅内部 loopback 18082，`X-Service-Token` 验证 /assist 与 /index；/health 只返回非敏感 readiness 信息。Java 不把客户令牌/角色或 MySQL 配置转发给 Python。检索来源使用 demo:// 标识，前端显示纯文本而非任意可点击 URL/HTML。

## v0.7 公开知识白名单

Java 调用 Python `/assist` 时可附加 publications（最多50个有效公开快照）；无白名单不检索公开集合。内部服务身份保护 `/publications/upsert`（document）与 `/publications/delete`（id），浏览器不能直接调用。Qdrant 独立集合使用真实 MiniLM 384维 cosine，payload 必须与请求快照及模型身份一致。Java 在回答入库、历史读取和旧草稿执行时再校验当前授权；撤回来源令整条旧回答降级，待执行草稿返回409 KNOWLEDGE_WITHDRAWN。公开来源 URI 为 knowledge://serviceflow/publications/UUID/vN。
