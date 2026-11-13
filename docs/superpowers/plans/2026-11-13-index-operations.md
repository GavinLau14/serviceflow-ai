# v0.8 知识索引核验与恢复

## 业务选择

不引入消息队列或新调度框架。先提供管理员可核验、可确认修复的运行面板，修复仅重建已经授权的同一公开快照，不创建新版本、不恢复已撤回授权。模型 Key 未配置，真实 LLM 验收继续标记未完成。

现有 SYNCED 表示最后写入确认，无法发现 Qdrant 数据丢失；sync 跳过 SYNCED。因此增加单版本核验和显式修复：即使历史 SYNCED，也可核验真实点与 schema，丢失/不符会停止该版本引用。不可达单独记录 UNAVAILABLE，不能当作“点丢失”或“健康”。

## 数据与并发

Flyway V9 增加 operation_revision、check_state、checked_at。check_state 为 UNKNOWN/CURRENT/MISSING/INVALID/UNAVAILABLE；检查不生成 embedding。每次核验或索引开始，在短事务内递增 operation_revision，网络返回仅能更新相同 revision 且 ACTIVE 的行。显式修复开始将 index_state=PENDING，保证未确认的修复期间不能引用；迟到核验不能覆盖更新的修复确认，迟到修复不能恢复撤回。

Python 受内部身份保护的 POST /publications/inspect 接受一个严格 PublicDocument。检查 collection schema（384维 cosine）、确定性 point ID、payload 全字段与模型身份、向量长度及有限数值；只返回 id/version/state，不返回陌生 payload。不存在集合或点为 MISSING，schema/payload/vector 不符 INVALID，网络异常 HTTP503。

管理员 GET /api/knowledge/index-operations?page=1&size=20&problemsOnly=false：服务端分页；汇总 ACTIVE、SYNCED、待索引、索引失败、待清理数量，列表含公开标题/版本与状态/最后核验时间，不含内部正文。状态统计取同一读快照。POST /knowledge/{id}/publication/check、/repair 都绑定精确 publicationId 和 confirmed=true，沿用管理员及知识范围校验。check 只核验 ACTIVE，repair 只重建 ACTIVE。旧版本清理仍用已有 withdraw 幂等 API。

## UI

知识工作台增加管理员专属可展开“索引运行状态”，默认不加载，避免无关查询。分页、问题筛选、状态与最近核验，核验无需额外弹窗，修复需确认“只重建已有授权摘要”。已撤回只显示清理重试，不提供恢复按钮。保留原发布面板和客户来源行为。

## 实现顺序与验证

1. Python 检查协议与真实 Qdrant 丢失/篡改/恢复测试先红后绿。
2. Java 权限、统计分页、丢失停止引用、同版本修复、不可达区别、迟到核验/修复与撤回竞态先红后绿。
3. 管理面板及浏览器合同测试；实际 Java→Python→Qdrant 手动删除公开演示点、核验、同版本修复、客户重新检索。
4. 全量 Java/Python/浏览器回归、构建与本地容器；独立审查，模块提交，经 PR/main 三项 CI 后发布 v0.8.0。保留故障恢复与模型质量的验证区别。
