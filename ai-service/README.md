# ServiceFlow AI · M2

Python 3.12 服务。真实 FastEmbed `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`（384 维）+ 外部 Qdrant；LangGraph `retrieve → decide → validate`。Python 不连接业务数据库，也不创建工单。

```sh
python3.12 -m venv ai-service/.venv
ai-service/.venv/bin/pip install -r ai-service/requirements.lock
# run-ai.sh 自动读取根目录.env并映射内部令牌；无需在终端粘贴密钥
export AI_QDRANT_URL='http://127.0.0.1:16333'
./scripts/run-ai.sh
```

默认监听 `127.0.0.1:18082`。检索门槛为可配置的 `AI_MIN_SCORE`（默认0.45；仅演示门槛，未经检索质量校准）。环境变量：`AI_LLM_API_KEY`、`AI_LLM_BASE_URL`（默认 OpenAI /v1）、`AI_LLM_MODEL`；同时配置 key/model 才启用 RAG 模型调用。未配置时只显示真实检索原文，`retrieval_only`，始终无提案。首次 `/index` 下载真实模型，可能较慢；缓存位于仓库 `.cache/fastembed`。模型下载或向量服务失败返回明确人工接管，不用假向量代替。

`GET /health` 只暴露 readiness、模式及是否配置模型；不会下载模型。`POST /index` 和 `POST /assist` 必须携带 `X-Service-Token`。`/index` 请求体固定为 `{}`，只导入随包提供的三条**虚构演示知识**，不接受远程 URL 或任意文档。collection 包含模型/维度/版本标识；重复索引幂等覆盖固定 ID，不删除其他 collection。

```sh
curl -sS -X POST http://127.0.0.1:18082/index -H "X-Service-Token: $AI_SERVICE_TOKEN" -H 'Content-Type: application/json' -d '{}'
curl -sS -X POST http://127.0.0.1:18082/assist -H "X-Service-Token: $AI_SERVICE_TOKEN" -H 'Content-Type: application/json' -d '{"message":"空调不制冷需要维修"}'
cd ai-service
.venv/bin/pytest -q
RUN_REAL_AI_SMOKE=1 .venv/bin/pytest -q -m real
```

`test_contract.py` 使用明确标识的 fake 检索/HTTP 模型，仅验证协议及拒绝路径；`test_real_smoke.py` 才实际下载/运行 embedding 并读写外部 Qdrant。模型提案必须来自唯一 `create_ticket` 工具，category/priority 限枚举，引用必须来自本次检索，回答必须逐字摘录引用知识中的连续原文（M2采用抽取式回答，避免模型杜撰政策或工单状态）；执行提案 title 为客户原文前80字、description 为原文全文。低证据、未知引用、非法工具或协议错误均无提案并转人工。`confidence` 是模型自报值，向量 score 不是校准概率。Java 仍需保存提案、客户确认、所有权和幂等校验后才能写业务数据。

本地已执行：28项隔离协议测试通过；另1项真实 FastEmbed+外部 Qdrant 测试通过（中文报修命中演示空调资料、无关黑洞问题无引用并转人工）。未配置真实 LLM key，远程模型生成未验证；fake HTTP测试仅证明工具协议与校验。
