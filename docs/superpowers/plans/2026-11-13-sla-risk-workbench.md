# v0.9 SLA暂停补偿与风险工作台

## 决策与边界

保持既有首次响应/解决时限配置，不编造品牌服务政策。补全已公开记录的局限：等待客户期间暂停解决时钟，客户拒绝后恢复处理时顺延等待时长；首次响应时钟不暂停，已触发升级不回滚、不重复升级。管理员/主管/客服按原工单范围查询风险队列，客户不可读内部SLA。

V10增加 resolution_paused_at 和 paused_micros。迁移时既有WAITING_CUSTOMER从迁移UTC时间开始累计，不伪造历史等待时长。状态转换与SLA更新、审计/outbox保持同事务，在工单乐观锁成功后锁SLA；进入WAITING开启暂停，退出到IN_PROGRESS/CLOSED时以非负微秒差累计，并顺延resolution_due、清除暂停起点。多轮累计，过期版本/事务失败不得重复补偿。Clock统一UTC，可注入推进时间测试；时钟倒退不产生负时长。

SLA详情增加 resolutionPausedAt、pausedSeconds、asOf；resolutionDue为已结算的截止时间，暂停中的额外等待在恢复后结算，界面明确说明。等待状态不触发解决超时，已逾期后进入等待不会抹除进入等待前的超时。

GET /api/operations/sla-inbox?page=1&size=20&filter=BREACHED&q=：filter为BREACHED/DUE_SOON/WAITING_CUSTOMER/ALL。固定临期窗口30分钟，响应asOf和dueSoonMinutes；计数和列表同一REPEATABLE_READ快照及同一Clock时刻。仅未关闭工单，实际待响应和未暂停解决的最早截止排序，临界点<=asOf为超时，(asOf,asOf+30m]为临期。按标题/编号做字面LOCATE，最多100字符。汇总active/breached/dueSoon/waitingCustomer/unassigned不受筛选关键词影响，仍严格权限限定。列表不返回内部备注或完整客户描述。所有列表链接再经过原工单详情权限检查。

## 前端

现有白色/叶灰工作台增加工作人员“服务时限”入口，队列式列表而非装饰数字大屏。超时/30分钟临期/等待客户/全部筛选，检索、分页、明确asOf、状态和处理入口，自动打开原工单详情继续处理，不增加绕过既有确认/版本校验的写操作。失败清空旧队列；角色/范围变化丢弃迟到请求，手机无横向溢出。

## 验证步骤

1. 先写暂停多轮、时钟边界、过期版本和回滚测试，以及风险权限/时间边界/分页/搜索/快照测试；运行红测。
2. V10、OperationsService暂停补偿、TicketService事务集成，SLA风险读模型/API；运行聚焦测试。
3. SLA工作台和详情字段，界面合同测试先红后绿，原有全量回归。
4. 真实容器流程：虚构工单处理→等待→客户拒绝→截止顺延，角色风险队列→详情→按原规则处理；不篡改现有业务工单时间。
5. 完整构建、测试、截图、独立审查和文档。单独模块提交、PR/main CI通过后发布v0.9.0。无Key继续明确远端LLM未验收。
