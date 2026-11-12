# forge-workflow · OA 审批工作流引擎

基于 **WarmFlow** + **LiteFlow** 的流程引擎，面向 OA 审批（请假、报销、会签、抄送、事件等待等）。114 个主类、7 个 Controller、无单元测试（依赖框架行为，由平台侧集成验证覆盖）。

> **不要与 Agent 编排画布混用**：`forge-agent` 里的「Agent 编排画布」是面向一次 LLM 任务的自研 JSON DAG（试运行、版本快照、运行回放）；本模块是面向人工审批的流程引擎。两者零依赖、互不调用，页面与文档中都做了命名区分。

依赖：`forge-api`、`forge-common-liteflow` / `-web` / `-mybatis` / `-doc` / `-mail` / `-sms` / `-push` 等。**不依赖任何 Agent 业务模块**。

## 结构

```
com.forgeagent.workflow
├── controller/       # FlwCategory / FlwDefinition / FlwInstance / FlwTask / FlwEventWait /
│                     #   FlwSpel / TestLeave（示例）
├── domain/           # FlowCategory / FlowInstanceBizExt / FlowSpel / TestLeave + bo/vo/context
├── service/          # IFlw*Service + impl（Definition / Instance / Task / Category / Spel /
│                     #   NodeExt / Common / TaskAssignee / ChartExt / WorkflowServiceImpl）
├── handler/          # 策略处理器（节点扩展、分配人、表达式）
├── listener/ event/  # 全局监听、副作用监听、工作流事件发布
├── liteflow/         # 组件链：start / instance / task / operation（Complete*、TaskOp*、InstanceDelete*）
│   └── resources/liteflow/instance-chain.el.xml、task-chain.el.xml
├── rule/             # SpelRuleComponent
├── common/           # 常量 / 枚举（按钮权限、节点扩展、任务分配人、操作类型、变量）
└── mapper/           # FlwCategory / FlwTask / FlwHisTask / FlwInstance / FlwInstanceBizExt /
                      #   FlwSpel / FlwUser / TestLeave Mapper（含 XML）
```

## 关键约定

- **LiteFlow 组件链**：实例启动、任务办理、实例删除都拆成 `Prepare → Load → Exists → Execute → Notify` 等可独立替换的组件，规则在 `liteflow/*.el.xml`；改流程行为优先加组件而不是改 Service。
- **SpEL 表达式**：`FlwSpel` 表存表达式，`SpelRuleComponent` 统一求值；表达式引擎在 `forge-agent` 的 `WorkflowExpressionEngine` 是**另一套**（用于 Agent 编排 DAG），不要交叉引用。
- **审批与事件等待**：`FlwEventWaitController` 处理定时 / 消息触发的等待节点；`WorkflowGlobalListener` / `WorkflowSideEffectListener` 负责副作用与消息推送。
- **表结构自检**：平台侧 `WorkflowSchemaGuard`（在 `forge-agent`）启动时自检工作流相关表结构，缺失输出 WARN 与脚本路径指引。**重跑旧脚本 `agent_mcp.sql` 会 drop 重建 `agent_approval` 并带回 `session_id NOT NULL` 约束**，导致含人工审批节点的工作流执行失败——升级存量库只执行新增脚本，不要重跑全量。
- **DemoGuardFilter**：`demo/` 下的 `TestLeave` 等是框架示例，生产可按需裁剪。

## 数据初始化

基座表 `backend/script/sql/mysql/forge_workflow.sql`（另有 postgres 版本）；Agent 工作流相关增量脚本 `backend/sql/agent_p2_workflow.sql`、`agent_step3.sql`、`agent_step4.sql`、`agent_step5.sql`、`agent_workflow_version.sql`、`agent_mcp.sql` 等。全部按字母序 ≠ 依赖序，**存量库只跑新增脚本**。
