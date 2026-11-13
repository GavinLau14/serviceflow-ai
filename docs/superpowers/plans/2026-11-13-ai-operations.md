# v0.10 AI运行观测与故障定位

状态：待开发。以下接口、界面与验证是计划，尚未实现或验收。

## 业务边界

没有模型Key，不能做真实模型质量验收。本轮提供管理员可用的调用元数据与健康核验，帮助定位调用失败、检索降级和建单关联；不把运行统计当模型准确率、降本比例或性能基准。只读功能，不改客户咨询、工具执行和引用授权。

仅ADMIN可访问运行记录、汇总及健康接口；员工和客户不可读取全局AI日志。只提供UUID、当时模式、记录耗时、引用条数、是否存有原始提案、工单关联、是否提交人工接管和时间；不返回客户身份、原消息、回答、引用正文、提案正文、hash、服务地址、Key或上游报错。

## API与数据

GET /api/operations/ai-runs?hours=24&page=1&size=20&mode=&linkedOnly=false：hours1至168，每页1至100，页码1至100000；mode仅空/rag/retrieval_only/disabled/error/unknown。按created_at降序及UUID稳定排序，同一UTC asOf及REPEATABLE_READ快照统计、计数和分页。窗口含[asOf-hours,asOf]。汇总不受mode和linkedOnly影响，含各模式、总数、有关联工单、已申请人工接管、存有原始提案和平均已记录耗时（无有效记录时null）。原提案记录不代表当前可执行，最终权限/到期/撤回仍由原API校验。

SQL先用JSON_VALID替换损坏JSON为{}，再只投影允许的元数据；未知模式统一unknown，不回传陌生字符串。损坏日志不能使运行台整体崩溃。引用非数组为null，负耗时不参与均值。V11仅新增(created_at,id)索引支持全局时间窗口，不引入新框架。

GET /api/operations/ai-health：管理员手动核验Python健康接口，3秒超时/4KB上限；只返回NOT_CONFIGURED/REACHABLE/UNAVAILABLE、固定模式、nullable retrievalReady/llmConfigured及checkedAt。缺字段/非法类型/矛盾模式当作UNAVAILABLE，异常文本不出服务边界。REACHABLE只表示健康接口可达，不代表服务鉴权、真实LLM调用或质量验收通过。

## 界面

管理员桌面和手机常驻导航增加“AI运行”，使用已有浅色工作台样式。明确区分健康核验与历史记录；显示真实降级/配置状态、时间窗口与模式筛选、关联工单筛选、分页和记录详情元数据。健康与记录分别加载/报错，失败清旧结果，不模拟数据；角色/范围变化丢弃迟到请求。可打开已关联工单，仍经原详情权限检查。不展示咨询/回答全文。

## 验证顺序

1. Java权限、时间窗口/过滤/分页/统计、损坏JSON和隐私字段拒露、健康成功/超时/异常脱敏测试先红后绿。
2. 新读模型/Controller、AiGateway短超时GET及V11索引；聚焦测试。
3. UI合同先红后绿，实际App手机管理员入口和非管理员无入口；真实查询现有日志、retrieval_only健康核验和关联工单跳转。
4. Java全量、Vue构建/浏览器、源码镜像与本地健康更新、截图、独立审查、文档。单独提交，经PR/main三项CI后发布v0.10.0；保留无Key验收边界和原始日志保留局限。
