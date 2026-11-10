# GitHub 技术评估（2026-11-10）

方法：读取四个默认分支 README、完整 LICENSE、构建描述、关键业务代码及代表性测试；浅克隆固定到以下提交。没有构建或运行这些上游项目，因此不能以 README 中的 production-ready 声明证明生产成熟度。未以 star 数判断成熟度。

| 仓库 / 审查提交 | 许可证 | 实际代码与成熟度信号 | 兼容性与取舍 |
|---|---|---|---|
| [escalated-spring](https://github.com/escalated-dev/escalated-spring/tree/76d8c9dde66f430fcf5eae1499c52d3b805dedff) | MIT，Copyright 2026 Escalated.dev | `TicketService.java` 使用事务、事件、工单活动记录；`EscalatedSecurityConfig.java` 区分角色入口；仓库有 171 个测试目录文件（不是测试用例数），并有包消费验证脚本。广度较大，也明确记录 newsletter 等部分实现边界 | 当前 `build.gradle.kts` 是 Boot 4.1.2 / Java 17 / JPA，并非可直接接入 Boot 3 / MyBatis 的模板。参考客户/客服入口隔离、活动轨迹和状态服务，独立实现 |
| [agent-desk](https://github.com/huabeitech/agent-desk/tree/9f211c0d799ebc57463d465fc7a34ce39a9e3754) | Apache-2.0，以 LICENSE 为准 | `internal/ai/rag/retrieve.go`、`retrieve_flow.go` 将知识库范围、向量检索、hydration、耗时分离；`application/runtime/agent_loop_engine.go` 对结构化 handoff 决策校验；`tooling/registry_test.go` 验证写工具确认、风险等级和每次运行调用上限。工程分层与测试场景值得借鉴，未做部署审计 | Go 1.26+ / Next.js / Qdrant；业务语言不兼容，借鉴 answerability gate、proposal 与 execution 分离、显式授权，不移植运行时 |
| [complaint-system](https://github.com/ccc644/complaint-system/tree/851226df5397cdee979ae0bb0adbff906024b227) | MIT，Copyright 2026 ccc644 | README 的客户确认、解决方案、知识沉淀闭环贴近售后；`backend/ai_engine/ai_engine.py` 有 `_hybrid_retrieve`、`semantic_search_kb`；`scripts/replay_retrieval_seed.py` 有空期望集、噪声、strict/relax 通道回放及失败退出码。脚本还明确弱候选回填边界，体现可审查性；AI 核心文件较大，需重新划分边界 | FastAPI / Vue / PostgreSQL / pgvector，不能复用数据库层到 MySQL / Qdrant。参考业务字段与检索评测方法，禁止 Python 直接写本项目业务库 |
| [helpdeskos-backend](https://github.com/xjmaine/helpdeskos-backend/tree/766309156d72ef7084657b86b43f03138e58d5b1) | MIT，Copyright 2026 xjmaine | `TicketService.java` 具备创建、分派、解决方案与事件发布；解决前校验 IN_PROGRESS，操作前校验负责人；`TicketServiceTest.java` 是 Mockito 服务测试。测试目录有 5 个文件，不能等同充分集成覆盖。README 的生产就绪需独立验证 | Boot 3.4.4 / Java 17 / JPA / MySQL / Redis，与 Java 方向接近，但替换 JPA 和 Angular 成本高。参考按业务域组织及角色/负责人双重检查，自行建立 MyBatis 集成测试 |

## 结论

不 fork 任一项目，不拷贝源码、提示词、SQL、前端资源或测试。以模块化 Java 单体作为唯一业务写入方，独立 Python AI 服务留待 M2。
业务流程参考 complaint-system，Java 服务职责参考 HelpdeskOS 和 Escalated，Agent 安全及检索可观测性参考 AgentDesk。

额外检索了 [Dify](https://github.com/langgenius/dify)：README 明确许可证为带附加条件的 Dify Open Source License，并非标准 Apache-2.0；其整个平台会遮蔽 Java + 独立 LangGraph 的作品目标，本阶段不引入。它不作为第五个已完成关键代码审计的仓库。

## 版本依据

- Java 21 LTS；Boot 3.5.16：已核对 [官方兼容文档](https://docs.spring.io/spring-boot/3.5/system-requirements.html)，兼容 Java 17–25。本项目主动留在 Boot 3 系列，避免引入 Boot 4 的 Jackson 3 迁移成本，不宣称它是最新主版本。
- MyBatis starter 3.0.x 对应 Boot 3 / Java 17+，参见 [官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。
- MySQL 8.4 LTS；Redis 7.4；Vue 3 / TypeScript / Element Plus，npm lock 固定实际解析结果。
- Python 3.12 用于后续独立服务，本机系统 Python 3.9 不作为项目运行时；M1 不安装 LangGraph、Qdrant，不伪装 AI 功能。
