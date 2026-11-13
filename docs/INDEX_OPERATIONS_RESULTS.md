# v0.8.0 索引运行状态与恢复验证

日期：2026-11-13；本机独立 MySQL/Redis、Qdrant、Java/Python/Vue 容器，无生产部署。

## 已交付

管理员专属可展开运行面板：分页、待处理筛选、有效授权/最近索引确认/失败/待清理汇总，以及每版本的最近核验状态与时间。核验真实 collection、确定性 point、公开快照和向量结构；缺失与内容不符会暂停引用，服务不可达单独展示。显式修复沿用已有 UUID/版本，开始即 PENDING，成功才重新可引用，修复申请与操作代次在同一事务记录审计。

operation_revision 防止迟到核验覆盖后来的修复；ACTIVE 条件保证修复不能恢复已撤回授权。网络调用在本地事务之外，旧版本残留清理沿用幂等撤回。

## 实际结果

- Java clean verify：95 项，失败/错误/跳过0；真实 MySQL/Redis。新增权限与分页、缺失/不符、不可达、同版本修复与审计、在途核验/修复与撤回测试；Python HTTP 回执为测试替身。
- Python pytest：40 项通过，含真实 MiniLM/Qdrant。新增鉴权/严格文档、异常脱敏、错误维度/非有限向量拒绝；真实测试覆盖缺失、payload篡改、重建与删除。
- Vue 类型检查与生产构建通过，Playwright 29 项通过（28项界面合同和1项真实多角色工单闭环）。首次全量浏览器命令因未注入本地演示密码在收集阶段退出，按现有要求注入后完整重跑通过，未修改测试绕过。
- 三镜像源码构建、健康启动通过。首次真实闭环启动早于前端就绪，连接拒绝后等待服务健康重跑通过。
- 真实故障演练：仅删除知识8公开v2的一个演示点；核验 MISSING/FAILED，客户来源404，AI检索不含该来源；页面取消修复无写入，确认修复后同一 UUID/v2 恢复 SYNCED，再核验 CURRENT，客户真实检索重新命中。无其他向量删除。
- 桌面与390px手机面板检查，无横向溢出或页面错误。独立只读代码审查未发现重要缺陷。

证据：[Java](verification/index-backend.txt)、[Python](verification/index-python.txt)、[浏览器](verification/index-browser.txt)、[构建](verification/index-build.txt)、[真实故障闭环](verification/index-live.json)、[桌面](images/index-operations-desktop.png)、[手机](images/index-operations-mobile.png)。

## 未完成与下一步

未配置模型 Key，真实远端 LLM 与 Tool Calling 质量未验收。核验不是持续监控，也不证明向量的语义质量；当前无自动重试或告警，PENDING/FAILED 可人工恢复。已展示或送往模型的内容不因撤回而收回，原始AI日志仍保留，清理为最佳努力。下一阶段优先完善真实业务的SLA风险处置视图，再在有Key时执行既有六场景模型验收。
