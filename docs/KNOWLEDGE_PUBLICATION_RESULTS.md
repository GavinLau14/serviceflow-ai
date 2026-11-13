# v0.7.0 知识发布验证

日期：2026-11-13。环境：本机 Docker Compose，独立 MySQL/Redis、Java、Python、Qdrant 和 Vue；没有生产部署。

## 已交付

管理员独立填写公开摘要、双确认脱敏与全部登录客户可见；仅审核通过的知识可发布。不可变版本、索引失败重试、替换和显式撤回，前端展示真实服务状态。客户可预览公开来源版本，不暴露原工单或员工标识。知识撤回同时约束新回答、历史回显和旧草稿执行。

## 实际验证

- Java clean verify：91 项，失败/错误/跳过均 0；真实 MySQL/Redis。新增 5 项包含权限、失败重试、替换、撤回及提交后的并发竞态。Python HTTP 回执为测试替身，不据此声称真实模型效果。
- Python pytest：34 项通过，包含 opt-in 真实 MiniLM/Qdrant 的发布、白名单、字段篡改拒绝及删除，使用独立临时 collection 并清理。
- Vue 类型检查/生产构建通过；Playwright 26 项通过，包含发布双确认、索引重试、只读权限及来源撤回后的界面刷新。
- 三个镜像源码构建并在本地重启，服务健康检查通过。
- 真实 Java→Python→Qdrant：知识 8 的虚构光栅观测摘要 v1 发布后被 retrieval_only 命中；公开来源仅 id/version/title/source/text 五字段。撤回后来源 404、历史无旧引用、新请求不引用旧版本；发布 v2 后重复撤回 v1 不影响 v2。保留明确标记的虚构 v2 供演示。
- 桌面与 390px 手机发布面板截图，手机工作台无横向溢出。

证据：[Java](verification/publication-backend.txt)、[Python](verification/publication-python.txt)、[浏览器](verification/publication-browser.txt)、[构建](verification/publication-build.txt)、[真实闭环](verification/publication-live.json)。

## 边界

未配置模型 Key，远端 LLM 与真实 Tool Calling 质量未验收；上述真实闭环不生成工具提案。旧提案撤回阻断由 Java 集成测试验证。索引状态表示最近一次确认，不持续保证向量存储健康；失败可重试。审核与公开授权分离，人工脱敏不能替代生产数据治理。撤回阻止后续授权，不收回已展示或已发送给模型的内容；原始 AI 日志仍保留于数据库。见 [ADR 0007](adr/0007-publication-and-revocation.md)。

下一步优先配置真实模型后运行既有六场景验收，再完善索引恢复、知识保留策略与运营观测；不增加无业务必要的新框架。
