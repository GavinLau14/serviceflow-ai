# 通知收件箱增补结果（2026-11-12）

## 已完成功能

- 新增本人范围的通知分页 API，支持全部/只看未读；准确统计全量未读数，不再用最近100条推算。
- 新增显式 confirmed=true 的全部已读，按登录账号更新，不接受客户端指定其他用户；重复执行返回 updated=0。
- V5 添加 (user_id,is_read,id) 索引。旧 GET /notifications 保留最近100条数组以兼容原演示脚本。
- 通知页面提供20条分页、筛选、全量未读数与批量确认；标记后重新读取服务端数据。未读末页清空后回退有效页。
- 筛选/翻页失败时不把旧列表冒充当前结果，显示可重试错误；保留来源工单权限检查、文本转义和键盘可操作控件。

## 实际验证

| 验证 | 结果与证据 |
| --- | --- |
| Java 新接口失败复现 | 实现前4项均因404失败，随后实现；本地 work/notifications-red.log |
| 完整 Java verify | 71项通过，0失败/错误/跳过，真实独立MySQL/Redis；verification/notifications-backend.txt |
| 浏览器失败复现 | 新筛选与批量控件最初不存在；另新增筛选失败旧列表残留的回归，先观察失败再修复 |
| 完整 Playwright | 16项通过，15项确定性UI夹具合同 + 1项真实四角色售后闭环；verification/notifications-browser.txt |
| Vue类型检查和Vite构建 | 通过；verification/notifications-build.txt |
| 源码容器与迁移 | api/web镜像构建并更新本地服务，healthy；V5与索引实际存在；verification/notifications-migration.txt |
| 真实通知UI/API | PASS：Alice总量95、未读94，成功翻页；Bob实际6条未读通过界面确认后清零，重复标记返回0，Alice未读数不变；verification/notifications-live.json |
| 桌面/窄屏视觉验证 | 已查看截图，390px无横向溢出（同时检查分页区域），无页面异常；images/notification-inbox-desktop.png、notification-inbox-mobile.png |

数据库集成测试覆盖108条记录后仍可翻页、未读筛选全量计数、大页码long offset、参数拒绝、匿名拒绝、管理员仍只看本人、批量操作显式确认、幂等性与跨账号隔离。测试使用独立 serviceflow_test，事务回滚夹具。实机操作仅将演示账号Bob现有6条通知标为已读，没有更改Alice已读状态。

本轮只修改通知链路、前端与文档；未重新运行没有改动的Python测试，也不引用旧Python测试作为本轮新验证。真实远端模型仍没有配置Key，M2真实模型验收保持未完成。

## Git与后续

本轮独立本地提交在 codex/notification-inbox，精确SHA见git log和交付回复。无远程推送、无生产部署。

通知数量依赖刷新，不是WebSocket实时推送；使用offset分页，大量新消息在翻页间到达时可能引起列表位置变化，可刷新回第一页。旧数组端点继续有100条上限，但新界面已不使用它。新索引和108条测试不构成大规模压测结论。

下一关键验收仍是本地配置真实模型并执行六场景LLM/Tool Calling验收；暂未配置时，可使用当前本地栈演示完整人工售后闭环和真实语义检索。
