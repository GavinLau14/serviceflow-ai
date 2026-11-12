# 版本历史

版本标签标识产品交付快照；Java/Python/npm 内部包版本独立维护。0.x 版本用于本地演示，不代表生产就绪。M2 的远端真实模型验收始终作为未完成项保留，不因版本递增而视为通过。

## v0.5.0 — 账号安全（2026-11-12）

- 本人修改密码、当前密码验证、二次输入和显式确认；Unicode字符数与BCrypt的72字节上限校验。
- MySQL凭据版本与密码哈希同事务更新，让全部旧登录会话的后续请求失效；旧用户快照签发的会话也不能绕过版本检查。
- 保持现有版本0旧会话兼容，失败/回滚不撤销原会话；没有修改本地现有演示账号密码。
- 增加账号安全抽屉、手机头像入口与输入清理；补强原有限流测试的可注入时钟和分钟边界。
- 本地 clean verify：Java79项通过；浏览器19项与前端构建通过。远端PR/CI及实机结果见 [账号模块验证](docs/ACCOUNT_SECURITY_RESULTS.md)。

## v0.4.1 — 客服工作台与通知完善（2026-11-12）

- 客服收件箱改版：持久队列、工单处理、客户属性、手机操作与退出入口。
- 合法 AI 转人工保留来源引用，删除低可信度草稿；新增六场景真实模型验收脚本，未配置时明确 NOT_RUN。
- 通知分页、只看未读、全量未读数、显式全部已读及跨账号隔离。
- 本地实际验证：Java 71 项、浏览器 16 项通过，源码容器和实机通知操作验证通过；Python 最近一次为 30 项通过（含真实 Embedding/Qdrant）。不同验证时间和范围见各结果文件。
- GitHub 公开交付、里程碑快照分支、版本标签和模块导航。

实现：`91ba988`、`d7fc8a1`、`224bbaf`。记录：[前端](docs/FRONTEND_REDESIGN_RESULTS.md)、[模型验收准备](docs/LLM_READINESS_RESULTS.md)、[通知](docs/NOTIFICATION_INBOX_RESULTS.md)。

## v0.4.0 — 全栈本地交付 / M4（2026-11-11）

完整 Docker Compose、Java/Python 非 root 源码镜像、演示脚本、故障降级和重启持久化验证、CI 配置与架构/API 文档。

提交：`e67ff4d`。[实际验证](docs/M4_RESULTS.md)。

## v0.3.0 — 协作运营 / M3（2026-11-11）

人工接管、自动分派、SLA 升级、事务 outbox/站内通知、审计、已解决工单人工知识整理与主管审核。

提交：`c7b06eb`。[实际验证](docs/M3_RESULTS.md)。

## v0.2.0-alpha.1 — AI 接口与真实检索 / M2（2026-11-10）

FastAPI/LangGraph、真实多语言 Embedding/Qdrant、引用、结构化输出、受约束工具草稿、客户显式确认、Java 幂等执行和调用记录。

这是预览版本：远端 LLM/Tool Calling 质量没有真实验收，不能用 fake provider 合同测试代替。无 Key 时明确 retrieval_only。

提交：`7d3ebc6`。[实际验证](docs/M2_RESULTS.md)。

## v0.1.0 — 工单核心 / M1（2026-11-10）

独立仓库/数据库、四角色权限、状态机、MyBatis/Flyway、公开回复/内部备注、乐观锁与基础 Vue 工作台、真实 MySQL/Redis 集成测试。

提交：`5afe8ec`。[实际验证](docs/M1_RESULTS.md)。
