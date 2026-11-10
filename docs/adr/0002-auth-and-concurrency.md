# ADR 0002 — Revocable sessions and optimistic updates

Status: accepted (2026-11-10).

使用 Spring Security + BCrypt + 256 bit 随机 opaque bearer，Redis 存 SHA-256 摘要映射、120 分钟 TTL。登出即时撤销，服务端每次从数据库读取活跃用户角色；不需引入 JWT refresh/blacklist 双重状态。Redis 故障时认证关闭。浏览器 sessionStorage 仅用于本地演示，仍需防 XSS；生产登录宜评估 HttpOnly cookie/CSRF 与组织 SSO。

列表与详情均使用 Mapper scope：客户本人；客服当前分配；主管同组；管理员全局。customerId/teamId/actorId 来自后端身份与分类规则，请求未知字段拒绝。

所有修改使用 `WHERE id=? AND version=?` 的 CAS，并在同一事务提交事件。双请求只有一次可以更新；后一个返回 409，不追加成功事件。评论也递增版本，避免旧详情覆盖新进展。分页 list/count 使用同一 scope，MySQL 默认 repeatable-read 事务获得一致视图。

M1 事件为业务流转轨迹，不冒充完整安全审计。M3 再加入不可变审计查询、拒绝操作审计与 outbox 通知。无压测结论。
