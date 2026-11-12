# forge-system · 系统管理基座

用户 / 角色 / 菜单 / 部门 / 岗位 / 字典 / 参数 / 通知 / 消息 / OSS / 登录日志 / 操作日志 / 在线用户 / 社交登录 / 个人中心等系统级能力。148 个主类、19 个 Controller、无单元测试（框架基座行为，由集成验证覆盖）。

依赖：`forge-api` 与 `forge-common-*`（core / doc / mybatis / translation / oss / log / excel / sms / security / web / sensitive / encrypt / push）。**不感知任何 Agent 业务模块**。

## 结构

```
com.forgeagent.system
├── controller/       # 19 个：SysUser / SysRole / SysMenu / SysDept / SysPost / SysDictType /
│                     #   SysDictData / SysConfig / SysNotice / SysMessage / SysOss(+Config) /
│                     #   SysProfile / SysLoginInfo / SysOperlog / SysUserOnline / SysClient /
│                     #   SysSocial / Cache
├── domain/           # 实体 + bo/vo（SysUser、SysRole、SysMenu、SysOssConfig 等）
├── mapper/           # 20+ Mapper（含 mapper/system/*.xml）
├── service/ impl/    # Sys*ServiceImpl（含 SysDataScopeServiceImpl 数据权限、
│                     #   SysPermissionServiceImpl 权限、SysSensitiveServiceImpl 脱敏）
├── listener/ event/  # OnlineUserCleanListener / OssConfigChangeListener /
│                     #   SysUserImportListener + 对应事件
└── SystemApplicationRunner  # 启动初始化（缓存预热等）
```

## 与本平台其他模块的关系

- **登录与鉴权**：Sa-Token 登录入口在本模块，`/auth/login` 带 `@ApiEncrypt`（明文请求 403，调试端需先取 encrypt-key，模板 `temp/A1Login.java`）。平台业务资源的空间级可见性由 `forge-agent-core` 的 `ResourceAccessGuard` 负责，本模块只管系统级角色与菜单权限。
- **工作空间成员**：Agent 平台的工作空间 / 成员实体在 `forge-agent-core`（`AgentWorkspaceMember` / `WorkspaceRole`），**不在这里**，不要混用两套角色模型。
- **OSS 配置**：`sys_oss_config` 由本模块维护；呼叫中心录音的 `oss` 存储实现（`forge-callcenter`）会读它。配置变更发 `OssConfigChangeEvent`。
- **Actuator 与监控**：端点已收窄为 `health,info,metrics,loggers,threaddump,httpexchanges`，`show-details: WHEN_AUTHORIZED`；Basic 认证凭据出厂值在根 `pom.xml` 的 `monitor.username/password`，生产必须更换。

## 数据初始化

基座表 `backend/script/sql/mysql/forge_vue.sql`（另有 postgres 版本），导入务必带 `--default-character-set=utf8mb4`，否则中文双重编码。Agent 平台业务表在 `backend/sql/agent_*.sql`，与本模块表分开维护。

## 上线注意

- 更换 JWT 密钥、RSA 前后端加解密密钥对（与前端 `VITE_APP_RSA_*` 成对轮换、同窗口发布）、默认数据库 / Redis / snail-job token、Actuator Basic 凭据；
- 只执行新增增量 SQL 脚本，不要重跑全量（字母序不等于依赖序）；
- 新增 Vo 必须挂 `@AutoMapper(target = Entity.class)`，否则 `selectVoPage` 抛 `ConvertException`，接口只回「发生未知异常 + 错误编号」。
