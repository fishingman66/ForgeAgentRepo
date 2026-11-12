# forge-agent-core · 共享内核

被多个业务模块共同依赖的实体、Mapper 与领域服务。23 个类、**无 Controller**（不对外暴露端点）、**无单元测试**（逻辑简单且被上游模块测试覆盖）。

依赖：`forge-common-core` / `forge-common-mybatis` / `forge-common-redis` / `forge-common-satoken`。**不感知任何业务模块**，也不反向依赖 `forge-agent` / `forge-rag` / `forge-memory`。

## 结构

```
com.forgeagent.agentcore
├── domain/           # AgentInfo / AgentModel / AgentWorkspaceMember / AgentChatAttachment / AgentAuditLog
│   └── vo/           # 对应 Vo + ChatAttachmentVo
├── enums/            # WorkspaceRole（OWNER / ADMIN / MEMBER 等工作空间角色）
├── mapper/           # 5 个 Mapper（含 AgentWorkspaceMemberMapper.xml）
├── service/
│   ├── ResourceAccessGuard      # 资源「平台共享 / 空间私有」双模式的唯一可见性入口
│   ├── AgentScopeModelFactory   # 从 AgentModel 配置构造 AgentScope 2.x Model
│   ├── AgentConfigCacheService  # 模型等配置的缓存
│   ├── WorkspacePermissionHelper# 空间成员与角色校验
│   ├── AuditLogHelper           # 审计日志写入
│   └── UtilityAgents            # 内置工具 Agent 常量
└── utils/ChatTexts              # 聊天文本处理纯函数
```

## ResourceAccessGuard（最重要的约定）

工具 / 数据源 / 模型三类资源带 `workspace_id`：`NULL` = 平台共享（仅超管可建改），非空 = 空间私有（仅归属空间可见）。存量资源默认共享，行为零变更。

- 列表查询把 `(workspace_id IS NULL OR workspace_id IN (...))` **下推到 SQL**，不是查出来再过滤；
- 登录态走 `assertReadable` / `assertWritable`，免登录链路走 `assertReadableBy`（以工作流归属 Agent 的空间作为 `callerWorkspaceId`）；
- 引用了空间私有资源的 Agent 禁止发布到市场；
- `agent.resource-sharing.enabled=false` 可一键收紧为「全私有」解释，此时存量 NULL 资源需运维先迁移归属。

升级脚本 `backend/sql/agent_resource_scope.sql`（幂等）；新库由 `deploy/docker/mysql/init/01-schema.sql` 直接带列。

## 复用注意事项

- **`AgentScopeModelFactory.build(...)` 被多个域复用**：Agent 对话、RAG 图片描述（`KbImageDescriber`）、客服语义缓存等都经它构造 OpenAI 兼容模型，配置来自 `agent_model` 表（含 baseUrl / apiKey / modelName）。
- 审计日志 `AuditLogHelper.record(...)` 有多个重载；`R.ok(String)` 会命中 `ok(String msg)` 导致 `data=null`，**要返回字符串必须用 `R.data(...)`**。
- 新增 Vo 必须挂 `@AutoMapper(target = Entity.class)`，否则 `BaseMapperPlus#selectVoPage` 抛 `ConvertException`，而接口只回「发生未知异常 + 错误编号」。
- 本模块只放「被复用的定义」，业务流程一律放到上层模块；把 Controller / 业务 Service 放进来会立刻产生模块环。
