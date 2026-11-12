# 账号安全模块结果（2026-11-12）

## 已实现

登录用户可修改本人密码：验证当前密码、新密码字符/UTF-8长度、与旧密码不同、二次输入及显式确认。账号锁内同时更新BCrypt哈希与凭据版本，所有旧会话后续鉴权失效；旧快照签发的token也无法恢复权限。事务回滚保留旧密码与会话，不进行Redis全库扫描或密码双写。

前端左下角头像进入账号安全，桌面/手机共用系统抽屉。错误可重试，关闭/退出清理输入，成功后清理当前登录态并提示重新登录。V6兼容原来版本0账号的旧会话，不重置已有演示账号。

## 实际验证

| 验证 | 实际结果与证据 |
| --- | --- |
| 接口实现前 | 测试先观察到端点404，再实现；本地 work/password-red.log |
| Java `clean verify` | 79项通过，0失败/错误/跳过；真实独立MySQL/Redis；verification/account-backend.txt |
| 账号集成测试 | 7项：多会话/旧快照失效、错误和确认边界、Unicode字节、旧格式兼容、本人范围、并发单胜、事务回滚 |
| 前端构建 | Vue类型检查与Vite通过；verification/account-build.txt |
| 完整Playwright | 19项通过（18项UI夹具、1项真实四角色售后闭环）；verification/account-browser.txt |
| 本地源码容器 | api/web构建并更新成功，healthy；V6实际迁移；verification/account-migration.txt |
| 迁移兼容实机 | 更新前签发的客户/客服/主管/管理员会话在更新后均可使用；错误密码修改被拒且原会话仍有效；verification/account-live.json |
| 现有密码保留 | 升级前后对比七个已有账号的密码哈希摘要，全部一致；verification/account-existing-credentials.json。未在报告保存哈希或密码 |
| 现有售后闭环 | PASS，真实检索3条知识、接管幂等、工单#30关闭、知识#6审核通过；verification/account-stack.json |
| 视觉 | 已查看稳定动画后的桌面/390px截图，抽屉完整位于视口内；images/account-security-desktop.png、account-security-mobile.png |

成功改密与回滚测试只在独立测试数据库的临时账号上执行，测试后删除账号/会话。现有本地演示账号仅测试读取和错误修改请求，没有成功修改其密码。

## 发现并处理的问题

- 首次完整verify的测试通过，但打包因target内的重复“... 2.class”生成产物失败；源码不存在重复主类，使用clean清理后重新完整验证。
- 随后复现旧登录限流测试跨分钟的偶发失败：运行从16:28:59.639跨到16:29:00，新的分钟窗口本应允许登录尝试。将时钟注入限流服务，测试使用固定/可推进时钟，并增加分钟切换测试，没有放宽限流上限。
- UI说明使用普通文本，错误才使用alert；手机测试定位具体账号抽屉并验证其边界，避免把其他隐藏抽屉或动画中的画面当作完成状态。

## GitHub与限制

功能分支codex/account-password，完成本地验证后通过PR远端CI再集成main；远端构建结果以PR/Actions实际状态为准。v0.4.1首次发布的三项远程CI已通过，见 verification/github-v0.4.1-ci.json。

没有新增忘记密码找回、管理员重置、MFA或密码历史；不强制中断已经通过鉴权且正在执行的其他请求。旧会话键按TTL过期。真实远端LLM验收仍待配置Key，本模块不改变该状态。无生产部署。
