# M2 真实模型验收准备补齐（2026-11-12）

本轮在已交付 M1–M4 和新版客服工作台的基础上补齐模型验收流程，不将远端模型质量标记为已完成。

## 已完成功能

- 标准库脚本 `scripts/llm_acceptance.py`：通过 Java 公开 API 验证六条固定场景、引用完整性、意图、两类工单草稿、咨询不建单、政策与越权问题转人工。
- 显式 `--run-live` 才允许发起模型请求；最多六次 assist，保留 AI 调用日志，不执行工单工具。未配置/知识未就绪返回 NOT_RUN、退出码 2。
- 校验调用历史、未绑定 ticketId、客户工单总数前后一致；脱敏报告，不保存 token、密码、模型 Key 或上游异常。全部通过才退出 0。
- 修复 Java 将合法、有引用的转人工结果改成 error 并丢弃引用的问题。现返回固定转人工说明、保留引用、confidence=0、移除提案；缺引用的 rag 仍失败关闭。
- 离线验收器回归已加入 CI 和 scripts/test.sh；新增配置说明与固定知识基线。

## 实际验证

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 修复前回归 | 新测试失败，期望 rag，实际 error | 本地 work/handoff-red.log（忽略目录） |
| Java `mvn -q -f backend/pom.xml verify` | 67 项通过，0 失败/错误/跳过；真实独立 MySQL/Redis | verification/llm-backend-tests.txt |
| 验收器离线单测 | 13 项通过；仅测试脚本，不证明模型质量 | verification/llm-harness-tests.txt |
| Python pytest | 30 项通过，包含真实本地 Embedding/Qdrant 正负检索 | verification/llm-python-tests.txt |
| 默认运行验收脚本 | 不发请求，NOT_RUN，退出 2 | 本地 work/llm-default-not-run.json |
| 无 Key 调用当前本地栈 | 第一条返回 retrieval_only，NOT_RUN，退出 2，不继续发剩余五条 | verification/llm-not-configured.json |
| API 源码镜像重建与本地更新 | 镜像构建成功，api 容器 healthy | 本地 work/llm-api-build.log、llm-api-start.log |
| 更新后完整业务 smoke | PASS；索引 3 条真实向量知识，人工接管幂等，工单 #26 CLOSED，知识 #5 PUBLISHED | verification/llm-readiness-stack.json |

Python 测试在一次性 AI 容器中挂载当前 tests 和 pyproject.toml，使用 `RUN_REAL_AI_SMOKE=1` 和已有模型缓存；不需要远端模型 Key。Java 测试首次完整运行仅新增测试的 HTTP 拒绝码断言有误（实际合同为 400），修正断言后重新执行完整 verify，67 项通过；没有为了通过测试放宽执行权限。

本轮前端源代码未改。仅更新本项目本机 API 容器；未执行远端 CI、推送或生产部署。

## Git 与剩余事项

独立本地提交位于 `codex/llm-acceptance` 分支，精确 SHA 见 git log 和交付回复。

仍未配置真实模型 Key，因此六场景远端生成/Tool Calling 结果尚未产生，M2 对应任务仍未勾选。下一步在本地 `.env` 配置供应商、模型与 Key，重建配置后的 AI 容器，按 LLM_ACCEPTANCE.md 执行并根据真实失败结果调整适配器或提示词。只有六条全部实际通过才能记为这组六场景验收通过；不宣称生产准确率或跨供应商兼容性。
