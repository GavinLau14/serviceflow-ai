# Local stack and operation runbook

仅用于本机演示，不是生产发布配置。所有业务组件使用同一ServiceFlow独立Compose项目；不会操作ProcureGuard。Java/AI镜像以非root运行；Python仅获得模型/Qdrant/内部token，不获得MySQL凭据。

## 从源码启动

```bash
./scripts/stack-up.sh
# http://127.0.0.1:18080
python3 scripts/smoke-stack.py --with-ai
```

需要Docker Compose v2与联网下载基础镜像、Maven/npm/Python依赖及Embedding模型。源码镜像构建不依赖本机Java/Node/Python虚拟环境。`--with-ai`只适用于未配置远端LLM的真实检索验收；配置远端模型后应另做生成及工具调用验收。第一次索引会下载约240MB模型，可在管理员页面执行。普通smoke（不带该参数）不下载模型，验证无知识时安全转人工、建单与关闭审核闭环。

.env由初始化脚本生成并保留既有值，Docker上下文排除.env、密钥、模型缓存、node_modules、target与虚拟环境。改变已有数据库密码环境变量不会自动修改数据库账号；不要通过删除数据卷解决凭据不一致。已有演示账号密码同理。

## 两种运行方式

- 本机开发：Java18081、Vite15173、Python18082；MySQL13318/Redis16389/Qdrant16333。
- 全栈容器：浏览器18080→Nginx→内部Java18081→内部Python18082→Qdrant6333。Java/Python不映射新宿主端口。可并存开发进程，但共享业务数据与基础设施；通常选一种运行方式，避免两个SLA扫描器同时工作。

## 数据与健康

持久卷：mysql-data、redis-data、qdrant-data、model-cache。`docker compose --profile stack ps` 查看健康。AI health=HTTP可达并不代表知识已索引；/health的retrieval_ready与每次响应mode才说明实际就绪。Qdrant依赖启动后由客户端进行就绪/失败校验。Web健康只表示Nginx可用，API健康另外检查数据库/Redis。

```bash
docker compose --profile stack logs --tail 100 api ai-service web
docker compose --profile stack restart api ai-service web
# 停应用保留数据库/缓存：
docker compose stop api ai-service web
# 恢复应用：
docker compose --profile stack up -d --wait
```

不要使用down -v，除非明确要销毁本项目数据。当前未实现自动数据库备份/灾难恢复；正式使用前需建立备份、恢复演练、TLS、SSO/密码管理、访问隔离、限流和依赖安全审计。

## 故障演示

停止ai-service后Java仍可使用工单；新的AI咨询应明确error且无可执行提案。恢复服务后可重新检索。停止Redis时认证失败关闭，不允许降级为匿名写入。停止MySQL后健康异常，不能声称工单提交成功。

通知是事务outbox的站内投递，扫描最多处理配置批次；未实现外部邮件/短信。SLA等待客户时不升级解决超时，但恢复后仍按原绝对期限判断。公开部署不应沿用演示账号及未配置认证的基础设施。
