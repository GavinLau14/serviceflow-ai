# AI service boundary — M2 planned

M1 不运行 Python 服务、不调用 LLM/embedding、不连接 Qdrant，不提供假 RAG。前端和 `/api/capabilities` 均明确 `disabled`。

M2 计划：Python 3.12 / FastAPI / LangGraph，model adapter 与真实 embedding adapter 分离；Qdrant collection 按模型和向量维度标识；返回检索 chunk ID、来源和分数。低证据走人工接管提案。

Java 在用户上下文中校验工具提案与显式授权、版本和幂等键后执行写入。Python 不持有 MySQL 凭据、不接受任意 SQL、不信任模型声称的角色/客户 ID。模型失败不能返回伪成功工单状态。

尚无实现与 pytest 结果，不把本目录计为完成 M2。
