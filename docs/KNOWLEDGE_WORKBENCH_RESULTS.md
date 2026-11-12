# 知识审核工作台结果（2026-11-12）

## 已完成功能

- 工作人员知识历史分页、状态筛选、标题/正文字面量检索；显示当前账号全可见范围的待审核数。
- 列表/筛选计数/待审计数在同一只读快照查询，共用工单权限条件。提供来源工单、提交人及审核人显示名。
- 退回必须填写理由；结论/理由不能通过审核接口覆盖。相同结论及规范化理由重试幂等，冲突409，旧缺失意见保持NULL。
- 独立队列/阅读审核组件，主管默认待审，客服默认全部。支持错误恢复、查询失败时清除旧结果、末页回退、手机队列/详情切换。
- V7新增review_note与状态时间索引。保留旧GET /knowledge数组；原审批请求仍可通过，退回请求需增加note。

## 实际验证

| 检查 | 结果与证据 |
| --- | --- |
| 实现前Java回归 | 6项失败：分页端点404、缺理由的退回仍200、意见字段尚不支持；本地work/knowledge-red.log |
| Maven clean verify | 86项通过，0失败/错误/跳过，真实独立MySQL/Redis；verification/knowledge-backend.txt |
| 新知识集成/并发 | 6项查询/审核测试 + 1项双人真实提交：108条历史、四角色范围、字面量检索、理由与幂等、输入边界；不同意见并发仅一项200、另一项409，仅一个审核事件和业务审计 |
| Vue类型/Vite构建 | 通过；verification/knowledge-build.txt |
| 完整Playwright | 23项通过，22项UI夹具合同 + 1项真实四角色售后闭环；verification/knowledge-browser.txt |
| 源码容器/V7 | api/web镜像构建并更新，healthy；V7与索引实际存在；verification/knowledge-migration.txt |
| 实机业务 | 工单#34由客户关闭，草稿#7退回并保留理由，修订内容另建#8并审核通过；重复幂等/不同理由409、跨团队查询隔离、客户403；verification/knowledge-live.json |
| 数据与边界 | 另留#9演示记录模板待审；所有新案例明确标为虚构。没有删除或覆盖原业务记录；Qdrant点数前后均为3，无自动发布 |
| 视觉 | 已查看稳定请求/过渡后的桌面和390px截图，无页面异常或横向溢出；images/knowledge-workbench-desktop.png、knowledge-reviewed-desktop.png、knowledge-workbench-mobile.png |

Java夹具只在独立测试数据库事务回滚或显式清理。实机流程通过Java API和真实网页执行，使用现有演示角色，没有改密。审核前后的Qdrant点数检查只作为本轮边界观察；代码没有新增索引调用，不据此宣称知识库安全审计完成。

## 版本、限制与后续

功能分支codex/knowledge-review-workbench，经PR远程检查后集成main，交付v0.6.0。精确提交及远程CI以PR/Actions和Release为准，旧版本记录不改写。

文本查询采用数据库字面量检索，没有大规模压测结论。offset分页期间有新记录可能移动列表位置；当前只提供同源工单下的新草稿，不提供显式修订父链/差异对比。审核记录的保护属于应用接口约束，不防数据库管理员篡改。

真实远端LLM质量仍因未配置Key而待验收；本轮未改Python，不把此前Python结果当作本轮新验证。下一步可在配置模型后执行既有六场景验收，并单独设计知识的受控发布/撤回流程。
