# ADR 0001 — Java owns business state

Status: accepted (engineering decision, 2026-11-10).

Java 模块化单体持有业务数据库写权限，MyBatis 显式 SQL 便于讲解与审查范围过滤。AI 是独立、不可信的建议生成方；未来只返回类型化提案，通过 Java 的角色、数据范围、参数、显式确认、版本、幂等性检查后才执行。

不选择直接 fork 上游或引入通用 Agent 平台，因为 Java 业务边界和安全工具执行应成为作品可审查的主体。代价是需自行实现并验证必要工作流。

M1 敏感动作：分派需要 confirmed；客户关闭/拒绝需要本人 confirmed。不能以管理员权限替客户确认解决。
