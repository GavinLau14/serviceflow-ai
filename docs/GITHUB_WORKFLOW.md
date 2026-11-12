# GitHub 版本与分支管理

公开代码仓库：[GavinLau14/serviceflow-ai](https://github.com/GavinLau14/serviceflow-ai)。用于简历展示、源码审查与本地复现，GitHub 托管代码不等于已上线公网服务。

2026-11-12首次公开上传：main=`d80dd4022b08f0d1a113b888b26d7d16fa930022`，远端SHA与本地一致，五个Release均已创建。首次 [远程CI](https://github.com/GavinLau14/serviceflow-ai/actions/runs/43181146777) 的verify、ai-contracts、container-smoke三项全部成功，保存记录见 verification/github-v0.4.1-ci.json。历史快照上传触发的重复CI已取消，不代表其失败或通过；这里只引用main实际成功运行。

## main、功能分支和版本

main 保留当前可演示的集成版本；新功能从最新 main 创建 `codex/<module>-<feature>`，完成模块测试、业务闭环和结果报告后再集成。不能通过改写历史报告把未完成的验收写成已通过。

| 里程碑 | 历史快照分支 | 标签 | 实现提交 |
| --- | --- | --- | --- |
| M1 工单核心 | codex/m1-foundation | v0.1.0 | 5afe8ec |
| M2 AI 与检索预览 | codex/m2-ai | v0.2.0-alpha.1 | 7d3ebc6 |
| M3 协作运营 | codex/m3-collaboration | v0.3.0 | c7b06eb |
| M4 容器交付 | codex/m4-delivery | v0.4.0 | e67ff4d |
| 后续完善 | codex/frontend-redesign、codex/llm-acceptance、codex/notification-inbox | v0.4.1 | 见 CHANGELOG |

历史快照分支和标签在首次 GitHub 发布时根据已有提交建立，原始提交时间、作者、内容保持不变；没有补造历史 PR 或声称它们曾在 GitHub 完成评审。标签是固定快照，后续不移动或覆盖。

## 发布要求

- README 说明业务模块、启动方式、示例角色、当前局限；CHANGELOG 区分每个交付版本。
- 报告写清实际命令、测试范围、结果和未完成项；GitHub Actions 状态以实际 run 为准。
- 推送前检查暂存内容及历史，排除真实 .env、密钥、模型缓存、数据库卷、构建产物和个人工作文件。CI 的明确测试凭据不是生产凭据。
- 当前未给原创代码指定开源许可证；公开可供审查不代表可任意复用。第三方来源和依赖许可边界见 THIRD_PARTY_NOTICES.md。
- 本次用户明确授权公开创建仓库和上传；后续可以继续在本仓库推送已验证的项目改动，不扩大为修改其他仓库或生产部署的授权。

检查远程：

```bash
git remote -v
git ls-remote origin refs/heads/main
gh run list --repo GavinLau14/serviceflow-ai --limit 5
```
