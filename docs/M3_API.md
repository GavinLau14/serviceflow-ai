# M3 协作接口

沿用 M1 bearer 认证和工单数据范围。

通知分页、未读计数及显式全部已读的增补接口见 [API 文档](API.md#通知收件箱m3-增补)。旧通知数组端点仅保留兼容用途，新工作台改用分页收件箱。

- `POST /api/ai/runs/{id}/handoff {confirmed:true,category:"DEVICE"|"APPLIANCE"}`：客户本人明确转人工，保留新咨询原文；与 AI execute 共用行锁和 ticketId，重复/并发不重复建单。旧记录缺少可验证原文时409，要求重新咨询。人工服务沿用工单公开消息，已分派客服开始处理即接管；不宣称已实现即时聊天。
- `GET /api/tickets/{id}/sla`、`GET /api/tickets/{id}/audit`：客服、主管、管理员按工单范围读取；客户不可读取内部审计。
- `POST /api/operations/scan {confirmed:true}`：主管扫描本团队，管理员全局；定时任务按有界批次扫描。SLA 超时一次性升级优先级，保留审计/通知outbox。等待客户期间不触发解决时限升级；重新处理后按原绝对期限判断，未实现暂停时长累计补偿。
- `GET /api/notifications`：本人最近100条。`POST /api/notifications/{id}/read {}`：仅本人且幂等。
- `POST /api/tickets/{id}/knowledge {title,content,confirmed:true}`：客户确认关闭后的工单，由有范围权限的客服/主管/管理员手动整理脱敏草稿。相同工单当前DRAFT复用，审核后可再建新草稿。
- `GET /api/knowledge`：兼容旧脚本的工作人员最近100条数组；新工作台用分页inbox。`POST /api/knowledge/{id}/review {approved:true|false,confirmed:true,note?:string}`：主管本组或管理员，退回时note必填，通过可选；重复同结论和规范化意见幂等，改变结论或意见409。审核通过不自动写入AI向量库。

自动分派：先锁团队、再建单，按同组活跃客服未关闭工单数量选最少负载，负载相同时按ID；配置容量不足保留OPEN。手工主管分派不受自动分派容量限制。系统分派的事件actor关联触发创建的用户，action为AUTO_ASSIGNED。

通知：业务变更、审计和outbox同事务，定时/显式扫描以唯一outbox_id投递站内通知；SKIP LOCKED+唯一约束避免重复。只发送固定元数据，不把内部备注、解决方案或知识正文复制到通知。没有邮件/短信渠道。

配置：SERVICEFLOW_AUTO_ASSIGN（默认true）、SERVICEFLOW_WORKFLOW_SCAN_ENABLED（默认true），其余app.workflows参数见application.yml。测试关闭默认自动分派/定时扫描，由专门测试显式开启。系统时钟UTC，可注入Clock进行时间测试。
