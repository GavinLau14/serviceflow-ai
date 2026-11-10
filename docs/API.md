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
