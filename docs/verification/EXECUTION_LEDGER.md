# M1 执行账本

Plan: docs/superpowers/plans/2026-11-10-m1.md

Ruling: 按用户明确的主推进授权直接设计与实施，不增加批准轮次；新建独立仓库 codex/m1，不需要从其他项目分出 worktree。
Ruling: 首轮只完成 M1。AI 目录只记录边界，不创建假实现或给 M2 打完成标记。
Ruling: 真实 MySQL + Redis 作为集成测试前提，不使用 H2；测试 Compose profile 可在 CI 映射到 service containers。
Pre-flight: auth User 身份由 Spring Security 注入，TicketMapper scope 使用相同 User，前端消费 Detail.allowedActions；DTO 与 API 文档匹配。

Task 1: complete — 登录/登出、Redis 会话、Flyway、角色数据范围。
Task 2: complete — 工单生命周期、评论/内部备注、CAS 与事务事件，包含独立并发竞争测试。
Task 3: complete — Vue 工作台、类型检查/构建、Playwright 四角色业务路径和窄屏无溢出验证。
Task 4: complete — README、API、ADR、参考评估、CI、演示数据和里程碑记录。

红绿证据：初始 login/lifecycle 测试得到 403 而预期 200；首轮 28 个测试通过；增加并发、限流、输入用例至 34；缺 version 回归测试得到 201 而预期 400，修复后 35 个测试通过。
浏览器初轮失败为 Element Plus 原生 input 被视觉标签覆盖；改用键盘选择和点击可见确认标签后完整业务通过。不声明该浏览器套件在 UI 实现前完成了红绿验证。

最终验证：格式化后 mvn verify 35/35；npm ci lockfile 重装与生产构建；重启开发服务器后 Chromium 完整业务通过（1 passed，4.2s）。截图已实际查看，桌面/390px 无遮挡、无横向溢出。依赖重装期间旧 Vite 缓存失效曾导致浏览器加载失败，重启后消除；没有通过重试配置隐藏失败。
