# Third-party notices

M1 的应用源码、SQL、测试及 UI 均为独立实现，未复制上述四个参考项目源码或资产。设计参考及固定提交见 docs/REFERENCE_REVIEW.md。

| 项目 | 许可证 | 著作权 / 使用方式 |
|---|---|---|
| escalated-dev/escalated-spring | MIT | Copyright (c) 2026 Escalated.dev；仅阅读业务设计 |
| huabeitech/agent-desk | Apache-2.0 | 以其 LICENSE 及各文件声明为准；仅阅读检索与工具安全设计 |
| ccc644/complaint-system | MIT | Copyright (c) 2026 ccc644；仅阅读流程和评测设计 |
| xjmaine/helpdeskos-backend | MIT | Copyright (c) 2026 xjmaine；仅阅读 Java 业务服务与测试 |

没有 vendored 源码，因此本记录不声称已满足未来复制代码时的义务。若未来复用任何部分：先核验对应提交许可证和 NOTICE，保留原始声明/完整许可证，标记修改，记录具体文件、版本和用途。

运行时第三方依赖由 Maven / npm 解析，不作为自有源码。发布二进制前需根据实际依赖树生成许可清单并审核传递依赖；本阶段不声称已完成完整 SBOM 或漏洞审计。
