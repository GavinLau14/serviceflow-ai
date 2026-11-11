# M4 实际交付记录（2026-11-11，Asia/Shanghai）

## 完成内容

从源码构建Java、Vue/Nginx、Python三个应用镜像，Compose stack profile集成MySQL、Redis、Qdrant，统一环回入口18080。新增健康检查、内部服务地址、独立模型缓存卷、排除密钥的Docker构建上下文、启动/业务验证脚本、部署恢复手册、架构说明、演示与面试路线。Java/Python容器均实测uid10001。

GitHub Actions配置包含Java/Vue/浏览器验证、锁定Python契约测试、完整镜像构建/业务smoke/AI故障降级/重启验证三个job。没有push，故没有远程Actions成功记录。

## 实际运行结果

- 三个镜像均实际从源码构建成功，镜像ID与Linux/arm64平台见verification/m4-images.txt。本机未验证amd64构建。
- MySQL/Redis/API/AI/Web健康检查通过；Qdrant经真实索引和查询验证。AI健康的HTTP200不等于模型/知识就绪，实际mode另外校验。
- 容器内真实下载约240MB多语言Embedding（约44秒），索引3条演示知识。没有复制宿主模型缓存冒充容器下载。
- 统一Nginx入口的认证业务smoke通过：真实检索引用、客户确认转人工、幂等回读同一工单、客服处理、客户关闭、主管审核知识、outbox投递通知。见m4-stack.json。
- 停止AI容器后，Java明确返回mode=error且无可执行提案；人工转接、处理、关闭和知识审核仍通过。见m4-ai-outage.json。
- force-recreate AI/API/Web应用容器后，精确读取首次smoke创建的已关闭工单，确认数据保留；模型缓存与真实检索也正常恢复。没有声称已完成数据库备份恢复演练。见m4-restart.json。
- 浏览器实际打开18080的静态生产构建，登录并查询真实引用，无pageerror；截图已查看，见images/m4-container-workbench.png、m4-container-retrieval.png。
- 主代理检查CI YAML、Compose配置、shell语法与镜像职责。未获得新的独立安全审计结论。

## 回归结果

- Maven test：66 tests，0失败/错误/跳过，BUILD SUCCESS；真实MySQL/Redis，44.888秒。
- Python：30 passed（1.59秒），含真实Embedding/Qdrant测试。
- Playwright：12 passed（11.8秒），其中11个明确接口替身/UI契约场景、1个真实四角色工单闭环。另有上述真实容器浏览器检索验证。
- 前端生产构建也在Docker多阶段构建内实际通过；不是只验证宿主dist或复制旧jar。

## 遇到的问题与处理

初次Maven构建访问上游声明的snapshot/milestone仓库，解析较慢；中止该次本项目构建，增加仅从Maven Central解析正式依赖的构建settings，重新构建成功。该限制只用于容器构建，不包含私有仓库凭据。

## 保留的局限

真实远端LLM生成/意图与Tool Calling质量仍因无Key未验收，不能把fake provider测试或retrieval_only当作完成这一项。演示知识只有3条，未经业务召回评测；本项目没有覆盖率、准确率或生产就绪宣称。

未执行远程CI、生产部署、压力测试、TLS/SSO、备份容灾或完整SBOM/漏洞审计。基础镜像使用明确发行线tag，但没有做字节级可复现构建承诺。通知仅站内，SLA/知识发布边界见M3_RESULTS和DEPLOYMENT。

目前只保留容器统一入口，已停止本项目原15173/18081/18082开发进程；未停止或修改ProcureGuard。业务/测试基础设施与原数据卷保留。

## Git / 下一步

本次单独M4提交，无远程推送，精确SHA见git log与交付回复。下一步应本地配置真实模型并完成M2剩余验收，再进行源码审查、演示排练和有针对性的安全/性能改进。
