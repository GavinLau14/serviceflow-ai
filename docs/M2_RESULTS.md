# M2 实现与验证（2026-11-10，Asia/Shanghai）

## 已交付

独立 FastAPI/Python3.12 服务、LangGraph retrieve→decide→validate、真实 FastEmbed 多语言384维模型、独立 Qdrant 容器/卷/端口、三条明确标注为虚构的售后知识、幂等索引、引用来源、可选 OpenAI-compatible LLM 适配器与 create_ticket Tool Calling。

Java 保存调用响应/输入哈希/耗时，校验模型输出、引用非空字段、必填 handoff 和提案与客户原文绑定；客户本人显式确认后行锁执行，同事务保存工单 ID，重复/并发请求只创建一张工单。未执行提案30分钟过期。客户历史只显示本人最近20次；管理员才可重建索引。Python 不持有业务数据库凭据。

前端包含实际运行模式、纯文本引用、人工降级、持久历史、过期草稿与确认执行；无 Key 时明确 retrieval_only，无任何可执行提案。

## 实际结果

| 验证 | 实际结果 | 证明范围 |
|---|---|---|
| Maven verify（另已完成clean verify） | 51 tests，0 failure/error/skipped，BUILD SUCCESS | 35个M1回归 + 15个AI协议/权限测试 + 1个并发执行测试；真实MySQL/Redis，AI上游明确为HTTP替身 |
| Python全量 pytest，RUN_REAL_AI_SMOKE=1 | 30 passed in0.83s | 29个契约/失败路径测试，1个真实FastEmbed与外部Qdrant测试 |
| Vue类型检查与生产构建 | vue-tsc/Vite build通过 | 前端可构建，不是只做静态页面 |
| Playwright全套 | 6 passed in9.5s | 5个清晰标注fake contract场景 + 1个真实工单四角色闭环 |
| 实际Java→Python→Embedding→Qdrant | PASS | 管理员经Java索引3条；客户AC查询命中demo-appliance-ac，无关查询零引用/转人工；未创建工单 |
| 实际浏览器检索 | PASS，截图已查看 | 无路由替身的真实检索原文、引用和历史界面 |

证据：[真实链路JSON](verification/m2-live-smoke.json)、[真实浏览器截图](images/m2-real-retrieval.png)、验证摘要见 verification/m2-*.txt。截图没有真实Key或客户隐私。

## 修复与审查

独立审查发现并修复：标准 tool_calls 的 content:null 被拒；Java/Python输入长度不一致；历史过期草稿仍为PENDING；缺失handoff和空引用可通过Java边界；Python内部token变量未与Java统一。另统一提案标题为前80个Unicode字符，保证最多160个UTF-16单元，避免emoji跨语言边界失败。新增回归测试，缺失handoff/空引用先观察失败再修复。提案标题/描述必须来自原始输入，正文采用引用原文约束。

一次打包失败由 target 中遗留的带“ 2”后缀重复 class 造成；未修改源码回避，clean verify 后成功。初选模型不受已安装FastEmbed支持，改用实际支持且已成功下载运行的多语言MiniLM。未使用合成向量代替真实模型。

## 尚未完成的验收与局限

**未配置远端LLM API Key，所以真实远端生成、意图提取及Tool Calling质量尚未验收。** 相关 adapter 已实现并通过明确替身契约测试（含标准content:null工具响应），不能把这些测试等同真实模型效果。配置本地.env中的AI_LLM_API_KEY/BASE_URL/MODEL后，还需要做真实报修提案→确认建单及拒绝路径验收。这是M2剩余验收项。

- 已实测的是本地真实Embedding/Qdrant与无Key retrieval_only链路，未声称端到端真实LLM通过。
- 当前仅三条演示知识、固定语料版本，阈值0.45未经业务数据校准，无召回率/覆盖率宣称。不能用它推断生产售后政策。
- 模型回答采用保守的引用连续原文约束，通用生成模型可能经常被拒绝；以安全失败换取可审查性，仍需真实数据评测。
- 完整自由文档上传/分块、KB审核、SLA、通知、知识沉淀均不属于本次完成范围。
- AI调用记录是客户可见运行记录，不是不可篡改审计；响应/提案包含原始问题，需要后续保留期及脱敏策略。
- HTTP适配器读取响应后再检查大小，本机受信服务范围适用；对公网不可信服务需额外流式大小/资源限制、限流和部署鉴权。
- CI已加入Python锁定依赖测试，但未推送，未运行远程GitHub Actions。未做生产部署或安全认证。

## Git与建议

本次单独M2提交包含实现和验证，精确SHA见交付回复及git log。保留M1历史与无remote策略。
下一步先补真实LLM验收（配置由用户本地填写，勿发送到聊天），再进入M3人工接管、规则分派、SLA和通知outbox。
