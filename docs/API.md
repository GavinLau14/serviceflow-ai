# REST API v1 (M1)

Base: `http://127.0.0.1:18081/api`。JSON，UTC 时间字段（无 Z 的日期时间按 UTC 解读）。除 login 外需 `Authorization: Bearer <token>`，健康检查为 `/actuator/health`。

| Method | Path | 请求/结果 |
|---|---|---|
| POST | /auth/login | `{username,password}` → `{token,user,expiresInSeconds}` |
| GET | /auth/me | 当前用户，无密码散列 |
| POST | /auth/logout | `{}` → 204，撤销令牌 |
| GET | /capabilities | `{milestone:"M1",aiMode:"disabled",ragEnabled:false,...}` |
| GET | /agents?teamId=1 | 同组主管/全局管理员；仅返回 AGENT |
| POST | /tickets | `{title,description,category,priority}` → 201 Ticket，仅客户 |
| GET | /tickets | `page=1&size=12&status=OPEN&q=关键词` → `{items,total,page,size}` |
| GET | /tickets/{id} | `{ticket,comments,events,allowedActions}` |
| POST | /tickets/{id}/assign | `{assigneeId,version,confirmed:true}` → Ticket |
| POST | /tickets/{id}/transition | `{target,version,confirmed,message}` → Ticket |
| POST | /tickets/{id}/comments | `{body,internal:false,version}` → 201 Ticket |

category = DEVICE/APPLIANCE（映射团队 1/2）；priority = LOW/NORMAL/HIGH/URGENT。title 1–160 字符，description 1–10000，message 1–2000，body 1–5000，均拒绝纯空白。分页 page 1–100000，size 1–100，q 最长 100，搜索为标题/编号的字面子串而非 SQL 通配符。未知 JSON 字段拒绝。

状态：OPEN →（主管分派）ASSIGNED →（当前客服）IN_PROGRESS →（提交方案）WAITING_CUSTOMER →（客户本人确认）CLOSED。客户拒绝回 IN_PROGRESS。主管可以在未关闭工单上更换同组客服。客服不能关闭工单，管理员也不能替客户确认。

客户仅看本人、客服仅看当前负责人为本人、主管仅同组、管理员全局。详情不存在与越权均返回 404。客户无法读写内部备注；已关闭禁止回复与分派。allowedActions 便于 UI，但不能代替服务端检查。

错误形状：`{code,message}`。400 参数/缺少确认；401 未登录/凭据错误/会话失效；403 操作角色不允许；404 对象不可见；409 VERSION_CONFLICT/INVALID_TRANSITION/TICKET_CLOSED；429 登录限流；503 认证/存储不可用。发生 409 应刷新详情、核对变化后重新提交。

示例（令牌由 login 返回，仅保存在当前 shell）：

```bash
curl -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:18081/api/tickets?page=1&size=12'
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"耳机无法开机","description":"已充电一小时，指示灯无响应","category":"DEVICE","priority":"NORMAL"}' \
  http://127.0.0.1:18081/api/tickets
```

M1 为手写接口文档，尚无自动生成的 OpenAPI/Swagger。禁止传真实密钥/支付信息到演示问题中。

## 通知收件箱（M3 增补）

所有角色均只能读取和标记本人通知，管理员也不例外。无需也不接受客户端指定 userId。

| Method | Path | 参数与响应 |
| --- | --- | --- |
| GET | /notifications/inbox | `?page=1&size=20&unreadOnly=false` → `{items,total,unreadTotal,page,size}` |
| POST | /notifications/read-all | `{confirmed:true}` → `{updated}`，仅更新本人未读通知，重复请求返回 0 |
| POST | /notifications/{id}/read | `{}` → Notice，其他账号记录返回 404 |
| GET | /notifications | 兼容旧演示脚本，返回最近 100 条数组；新界面使用 inbox 接口 |

Notice：`{id,ticketId,title,body,read,createdAt}`。page≥1，size=1..100，超出范围或非法布尔值返回400；未登录返回401。items按id倒序，total为当前筛选结果总量，unreadTotal为本人全部通知的未读总数，二者均不受当前页容量限制。使用long计算offset，避免大页码整数溢出。列表和两种计数在一次只读事务中读取。

全部已读是操作发生时对本人未读记录执行一次事务更新；之后新到达的消息仍未读。未读数量不是实时推送，进入页面、翻页、筛选、标记或刷新后重新向服务端读取。消息中的工单入口仍受工单数据权限检查，通知本身只包含固定动态说明，不包含客户问题或内部备注。

## 账号密码（v0.5.0）

`POST /api/auth/password`：`{currentPassword,newPassword,confirmed:true}`，成功返回204。只允许当前登录用户修改本人密码，不接受userId。新密码至少12个Unicode码点且不超过72个UTF-8字节，不得为空白或与当前密码相同；当前密码也需满足BCrypt的72字节输入上限。请求共享登录IP限流，超限429。

当前密码错误、长度不符、缺少确认或同密码返回400；匿名或已失效会话401。成功后要求重新登录，所有旧会话的后续鉴权请求均失效；已经通过鉴权且正在执行的其他请求不被强制中断。响应和日志不包含密码、哈希、token或credential_version。账号行锁、哈希更新和凭据版本递增在一个MySQL事务中完成，事务回滚时旧密码和旧会话保持有效。

V6增加credential_version默认0。Redis新会话记录账号ID及凭据版本；旧ID格式会话仅在账号版本0时继续有效，不需要扫描Redis全库。旧会话键按原TTL过期。更新账号能力时须同步更新API实例，本地演示不支持混跑忽略凭据版本的旧API实例。演示初始化器不会覆盖已存在账号的密码；用户改密后需用自己的新密码，修改.env不能重置已有账号。

## 知识审核收件箱（v0.6.0）

`GET /api/knowledge/inbox?page=1&size=20&status=DRAFT&q=关键词` → `{items,total,pendingTotal,page,size}`。page≥1，size为1..100，status为空或DRAFT/PUBLISHED/REJECTED；关键词去首尾空白后最多100个UTF-16字符。标题/正文用LOCATE字面量匹配，百分号、下划线和反斜杠没有通配符含义。非法参数400，未登录401，客户403。

items按createdAt/id倒序。total对应当前筛选，pendingTotal始终表示本人全部可见草稿数；三次SQL在同一REPEATABLE_READ只读事务中执行。客服限当前受派工单、主管限当前本组工单、管理员全局。范围使用相同SQL条件，同时约束列表和两种计数。offset用long计算。

分页条目除原知识字段外，包含reviewNote、authorName、reviewerName、ticketTitle、ticketReference；历史缺失可为null。旧GET /knowledge仍返回最多100条数组。知识审核/草稿写入返回基础知识字段，前端随后重新读取分页记录获取显示名。

`POST /api/knowledge/{id}/review {approved,confirmed:true,note?}`：退回时note必须非空白，通过时可选；原始输入最多1000个UTF-16字符，保存时去首尾空白。已审核记录只有相同结论、相同规范化意见才能幂等重试，不更新原审核人/时间；改变结论或意见409。旧历史没有意见时保持null，不补写历史。知识创建仍要求客户已确认关闭工单；审核按当前团队权限及显式确认执行，审核结果/意见/事件/审计在同一事务中提交。

审核内容只保留在业务数据库，审核意见不会进入站内通知正文，也不会自动加入AI向量库。被退回后可从来源工单提交新草稿，旧审核记录继续保留。当前没有知识条目的显式父版本关系或文本差异对比。
