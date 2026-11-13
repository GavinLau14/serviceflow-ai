# 业务模块与源码导航

Java 是业务权限和写操作的唯一入口，Python 负责推理与受约束提案。以下路径均为当前 main 的真实目录，不以未来规划充当已实现功能。

| 业务模块 | 入口与核心实现 | 关键测试 |
| --- | --- | --- |
| 账号/数据范围 | [auth](../backend/src/main/java/com/serviceflow/auth/)、[MyBatis 工单查询](../backend/src/main/resources/mappers/)、[账号安全](../frontend/src/components/AccountSecurity.vue) | WorkflowIntegrationTest、LoginRateLimitTest、PasswordChangeIntegrationTest |
| 工单状态机/沟通 | [ticket](../backend/src/main/java/com/serviceflow/ticket/) | WorkflowIntegrationTest、ConcurrentUpdateTest |
| AI 接入/客户确认/幂等 | [Java ai](../backend/src/main/java/com/serviceflow/ai/)、[Python core](../ai-service/serviceflow_ai/core.py)、[真实检索](../ai-service/serviceflow_ai/retrieval.py) | AiIntegrationTest、AiExecutionConcurrencyTest、Python test_contract / test_real_smoke |
| 分派/SLA/审计/outbox | [operations](../backend/src/main/java/com/serviceflow/operations/) | OperationsIntegrationTest |
| 通知收件箱 | OperationsController / OperationsService、[通知界面](../frontend/src/components/OperationsView.vue) | NotificationInboxIntegrationTest、operations-contract.spec.ts |
| 知识整理/审核 | [collaboration](../backend/src/main/java/com/serviceflow/collaboration/)、[KnowledgeWorkbench](../frontend/src/components/KnowledgeWorkbench.vue) | CollaborationIntegrationTest、KnowledgeInboxIntegrationTest、KnowledgeReviewConcurrencyTest、knowledge-contract.spec.ts |
| 受控知识发布/撤回 | PublicationController / PublicationService、PublicationPanel、Python retrieval | KnowledgePublicationIntegrationTest、test_publications / test_real_publication、publication-contract.spec.ts |
| 索引运营/恢复 | PublicationIndexController / PublicationIndexOperations、PublicationService、IndexOperations.vue、Python inspect_publication | KnowledgePublicationIntegrationTest、test_publications / test_real_publication、index-operations-contract.spec.ts |
| 客服工作台 | [App.vue](../frontend/src/App.vue)、[TicketDetail.vue](../frontend/src/components/TicketDetail.vue) | inbox-redesign.spec.ts、workflow.spec.ts |
| 部署/验证 | [Compose](../compose.yaml)、[infra](../infra/)、[scripts](../scripts/)、[GitHub Actions](../.github/workflows/ci.yml) | 容器 smoke、重启持久化、故障回退 |

## 建议阅读顺序

1. [演示流程](DEMO.md) → [架构与一致性边界](ARCHITECTURE.md) → [架构决策](adr/)。先理解客户确认和 Java/Python 的信任边界。
2. TicketController → TicketService → TicketMapper，结合权限、状态转换和过期版本的集成测试。
3. AiService → Python Assistant 的 retrieve/decide/validate → execute/handoff，阅读显式授权、来源绑定和并发幂等测试。
4. OperationsService 的团队锁、事务审计/outbox、扫描投递，再看知识审核与通知 API。

## API 与演示

[核心 API 与通知](API.md) · [AI API](M2_API.md) · [协作 API](M3_API.md) · [部署](DEPLOYMENT.md) · [模型验收](LLM_ACCEPTANCE.md)。

可在简历中描述已经实现且能从代码和测试证明的能力：数据权限、状态机与乐观锁、Java/AI 写入隔离、受约束草稿与幂等、真实向量检索、事务 outbox 和 SLA。不能写未经测量的准确率、吞吐量、降本比例或“真实模型已全面验收”。
