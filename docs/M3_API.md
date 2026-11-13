# M3 协作接口

沿用 M1 bearer 认证和工单数据范围。

通知分页、未读计数及显式全部已读的增补接口见 [API 文档](API.md#通知收件箱m3-增补)。旧通知数组端点仅保留兼容用途，新工作台改用分页收件箱。

- `POST /api/ai/runs/{id}/handoff {confirmed:true,category:"DEVICE"|"APPLIANCE"}`：客户本人明确转人工，保留新咨询原文；与 AI execute 共用行锁和 ticketId，重复/并发不重复建单。旧记录缺少可验证原文时409，要求重新咨询。人工服务沿用工单公开消息，已分派客服开始处理即接管；不宣称已实现即时聊天。
- `GET /api/tickets/{id}/sla`、`GET /api/tickets/{id}/audit`：客服、主管、管理员按工单范围读取；客户不可读取内部审计。
- `POST /api/operations/scan {confirmed:true}`：主管扫描本团队，管理员全局；定时任务按有界批次扫描。SLA 超时一次性升级优先级，保留审计/通知outbox。等待客户期间不触发解决时限升级；v0.9起恢复处理/关闭时按已记录等待时长顺延解决截止，首次响应时限不暂停。
- `GET /api/notifications`：本人最近100条。`POST /api/notifications/{id}/read {}`：仅本人且幂等。
- `POST /api/tickets/{id}/knowledge {title,content,confirmed:true}`：客户确认关闭后的工单，由有范围权限的客服/主管/管理员手动整理脱敏草稿。相同工单当前DRAFT复用，审核后可再建新草稿。
- `GET /api/knowledge`：兼容旧脚本的工作人员最近100条数组；新工作台用分页inbox。`POST /api/knowledge/{id}/review {approved:true|false,confirmed:true,note?:string}`：主管本组或管理员，退回时note必填，通过可选；重复同结论和规范化意见幂等，改变结论或意见409。审核通过不自动写入AI向量库。

自动分派：先锁团队、再建单，按同组活跃客服未关闭工单数量选最少负载，负载相同时按ID；配置容量不足保留OPEN。手工主管分派不受自动分派容量限制。系统分派的事件actor关联触发创建的用户，action为AUTO_ASSIGNED。

通知：业务变更、审计和outbox同事务，定时/显式扫描以唯一outbox_id投递站内通知；SKIP LOCKED+唯一约束避免重复。只发送固定元数据，不把内部备注、解决方案或知识正文复制到通知。没有邮件/短信渠道。

配置：SERVICEFLOW_AUTO_ASSIGN（默认true）、SERVICEFLOW_WORKFLOW_SCAN_ENABLED（默认true），其余app.workflows参数见application.yml。测试关闭默认自动分派/定时扫描，由专门测试显式开启。系统时钟UTC，可注入Clock进行时间测试。

## v0.7 公开知识发布（管理员）

所有接口需 Bearer 会话。GET `/api/knowledge/{id}/publication` 返回 latest/history（最多20版本），沿用员工原工单范围；客户403。

POST `/api/knowledge/{id}/publication`：`{expectedVersion:0,title:"公开标题",text:"人工脱敏摘要",redacted:true,confirmed:true}`。仅 ADMIN，原知识必须 PUBLISHED。标题160/正文1500字符，版本过期409，上限50 ACTIVE。相同内容和版本重试幂等。返回真实 status/indexState/cleanupState；FAILED 不可引用。

POST `/api/knowledge/{id}/publication/sync` 与 `/withdraw`：`{publicationId:"UUID",confirmed:true}`。重试不新增版本；撤回绑定精确UUID，重复撤回旧版本不影响新版。撤回授权先提交，清理失败仍禁止引用。

GET `/api/knowledge/publications/{UUID}`：全部已登录用户可读 ACTIVE+SYNCED 快照，仅 id（pub:UUID）、version、title、source、text；撤回或未同步404，不暴露原工单/知识/员工身份。原知识接口仍执行员工范围权限。

## v0.8 索引运营（仅管理员）

GET `/api/knowledge/index-operations?page=1&size=20&problemsOnly=false`：items/total/page/size/summary。页码1至100000，每页1至50。summary 为 active/synced/pending/failed/cleanupPending，均基于当前数据库状态；SYNCED 只是最近确认。问题筛选包含 ACTIVE 的未同步或核验服务不可达，以及 WITHDRAWN 的待清理记录。汇总不受筛选影响。

POST `/api/knowledge/{id}/publication/check`、`/repair`：`{publicationId:"UUID",confirmed:true}`。核验只读真实向量并记录 CURRENT/MISSING/INVALID/UNAVAILABLE 与 checkedAt，缺失/不符会将 indexState 变为 FAILED；不可达保留上次索引状态而明确标记不可达。修复重建相同授权版本，期间 PENDING，并记录 KNOWLEDGE_INDEX_REPAIR 申请审计；撤回版本409。Publication 元数据新增 checkState/checkedAt。旧回执按 operation_revision 失效，不保证跨存储原子性。

## v0.9 SLA 风险队列与暂停补偿

GET `/api/operations/sla-inbox?page=1&size=20&filter=BREACHED&q=`：仅授权工作人员，客服按受派工单、主管按团队、管理员全局，客户403。只含未关闭工单，筛选为BREACHED/DUE_SOON/WAITING_CUSTOMER/ALL；临期固定30分钟，截止瞬间为超时，之后至30分钟边界（含）为临期。标题/编号字面检索最长100字符，页码1至100000，每页1至100。

响应items/total/page/size/asOf/dueSoonMinutes/summary；summary含active/breached/dueSoon/waitingCustomer/unassigned，不受检索与筛选影响，仍执行相同范围。待处理的最早截止优先，同期限按id；等待客户且已首次响应的工单无nextDue。列表不提供完整客户描述或内部备注，查看与处理仍使用原工单API及版本/确认。汇总、计数、列表来自同一数据库读快照和同一个UTC asOf；不是实时推送。

原 GET `/api/tickets/{id}/sla` 新增resolutionPausedAt、pausedSeconds、asOf。进入WAITING_CUSTOMER记录暂停起点，离开到IN_PROGRESS/CLOSED后按非负微秒差顺延resolutionDue并累计，多轮只结算一次。pausedSeconds为已累计+当前等待的总秒数（向下取整）；暂停期间resolutionDue展示已结算截止，本轮等待在恢复时结算。时钟倒退不会产生负补偿；已升级不回滚也不重复升级。历史WAITING工单从V10迁移UTC时刻开始记录，不声称恢复未记录的历史等待。
