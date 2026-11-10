# M1 交付与实际验证（2026-11-10，Asia/Shanghai）

## 已完成功能

- 独立仓库和 `codex/m1` 分支，独立 MySQL/Redis 容器、数据库、数据卷与端口；未修改 ProcureGuard。
- 四仓库 README、LICENSE 和关键代码审阅；独立实现，无上游源码复制。
- Java 21 / Spring Boot 3.5.16 / MyBatis 3.0.5 / Flyway；BCrypt 登录、Redis 可撤销会话、限流、四角色数据权限。
- 建单、分页与字面搜索、同组主管分派、客服处理/解决方案、客户拒绝或确认关闭；公开回复/内部备注；事务事件轨迹和版本 CAS。
- Vue 3 / TS / Element Plus 工作台；真实 API、加载/空/错误/过期状态、响应式详情；未实现 AI 明确标为 disabled。
- .env.example、随机本地配置、启动/测试脚本、幂等 API 演示数据、README、Mermaid 架构图、REST API 文档、ADR 与 CI 配置。

## 后端构建与集成测试

在格式化与修复后的最终源码执行 `mvn -f backend/pom.xml verify`：

```text
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 22.877 s
Finished at: 2026-11-10T18:45:09+08:00
```

33 个业务/权限/输入契约测试 + 1 个并发测试 + 1 个限流测试。运行的是真实 MySQL 8.4 和 Redis，没有 H2 或数据库替身。详见 [JUnit 摘要](verification/junit-summary.txt)。

并发测试同时发送两次 version=0 的分派：响应分别为 200 / 409；数据库 version=1；事件只有创建与一次成功分派。测试不是仅验证 mock 调用次数。

## 前端

`npm ci --offline --cache <本次下载缓存>` 成功从 lockfile 重新安装（不是跳过安装）；随后 `npm --prefix frontend run build` 完成 vue-tsc 和 Vite production build。

```text
VITE 7.3.8
1609 modules transformed
index JS 376.99 kB / gzip 133.15 kB
CSS 377.17 kB / gzip 51.96 kB
built in 1.73s
```

完整 Element Plus CSS 仍较大，后续可按需拆分。npm 当次审计未报告漏洞，不等于完整 SBOM/供应链安全结论。

## 运行与浏览器

独立业务/测试 MySQL、Redis 均通过 Compose healthcheck，API `/actuator/health` 返回 `{"status":"UP"}`。
Playwright 已实际跑通客户提交 → 主管确认分派 → 客服开始处理/提交方案 → 客户确认关闭，检查浏览器无 pageerror，并在 390px 视口检查无横向溢出。
最终 Chromium 重跑：`1 passed (4.2s)`；桌面与窄屏截图已人工查看，确认无确认框残留或文字溢出。依赖重装曾使运行中的 Vite 旧缓存失效，重启开发服务后通过。最终浏览器日志与截图随本次交付保存于 `docs/verification/`、`docs/images/`。

## 已修复问题

见 [独立代码审查记录](verification/REVIEW.md)：CI fixture 密码不一致；version 缺省/null 被 primitive 接收。两项均已修复，后者有先失败后通过的回归测试。浏览器初轮 locator 操作受 Element Plus 视觉层覆盖，已改为键盘/可见标签操作。

## Git

- 参考评估与设计提交：`07908b0`。
- M1 实现与验证：本文件随 `feat(m1): deliver scoped after-sales ticket workflow and verified Vue workspace` 提交；精确 SHA 见本地 `git log -1` 和交付回复。
- 无 remote，无 push，无生产部署；CI 文件已编写，但尚未在 GitHub Actions 上运行。

## 尚未完成与下一步

M1 可演示的核心范围已实现。没有压测、生产部署、安全认证、完整审计模块或自动 OpenAPI 导出；Flyway 对 MySQL 8.4 仍给出版本测试警告，实际 migration 已成功；测试有 Mockito 动态 agent 警告。上述限制如实保留。

下一步 M2：独立 FastAPI + LangGraph、真实 embedding/Qdrant、检索引用与正负回归集、结构化提案、Java 授权与幂等执行、AI 调用日志。需要用户选择/提供模型服务配置才能验证真实远端模型，不预填任何 Key。M3/M4 详见任务清单。
