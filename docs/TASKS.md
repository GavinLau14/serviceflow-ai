# 任务与验收

## M1 — 当前交付
- [x] 四仓库 README / license / 关键代码评估和固定提交记录
- [x] 独立 Git / 数据库 / Redis / Compose 名称与端口
- [x] Flyway、MyBatis、登录撤销、角色与数据范围
- [x] 建单、分页检索、分派、处理、解决方案、拒绝/确认关闭
- [x] 公开回复、内部备注、状态事件、乐观锁
- [x] Vue 工作台、错误/空/加载/窄屏状态
- [x] 最终构建、完整测试与浏览器闭环（实际结果见 M1_RESULTS.md）
- [x] 本地 M1 commit（无远程，见 git log）

## M2 — 实现交付，远端模型验收待配置
- [x] 独立 FastAPI / LangGraph、配置验证与 pytest
- [x] 真实 embedding / Qdrant 索引与查询；模型、维度、版本追踪
- [x] 意图/字段结构化输出；引用可回溯；证据不足转人工
- [x] 工具提案、显式授权、受限服务身份、幂等执行、AI 调用日志
- [ ] 有 Key 的真实 LLM/Tool Calling 验收（待本地配置）；真实 Embedding/Qdrant 正负检索已验证，无 Key 为 retrieval_only

## M3
- [ ] 会话与人工接管、规则自动分派、负载/并发边界
- [ ] SLA 定时扫描、去重升级与可测试时间
- [ ] 事务 outbox / 站内通知 / 完整审计查询
- [ ] 已解决工单经人工审核后知识沉淀

## M4
- [ ] 包含 Python/Qdrant 的完整部署与健康检查
- [ ] 全套 CI / Demo / API 与架构文档
- [ ] 失败恢复、模型评测与已知局限清单
