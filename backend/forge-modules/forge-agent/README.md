# forge-agent · Agent 平台业务与运行内核

平台主体模块：工作空间、模型与供应商、工具与数据源、编排画布、对话运行、市场、触发器、审计计量、治理与评测。250 个主类、27 个 Controller、**101 个单元测试**（15 类）。

依赖方向：`agent-core ← forge-agent`，并依赖 `forge-rag`（检索）、`forge-memory`（记忆注入）、`forge-kg`（经 memory/rag 间接）、`forge-common-*`。`forge-customer-service` 通过本模块的 `api.customerservice` 契约包**单向依赖**本模块，反向禁止。

## 结构

```
com.forgeagent.agent
├── api/customerservice/  # 给客服域用的契约门面（CsChatFacade / CsKnowledgeGateway /
│                         #   CsRunOwnership / CsTraceExtender / CsGovernanceFacade + Impl）
├── config/               # AgentRuntimeProperties / AgentChatProperties / 调度池 / 过滤器
├── controller/           # 27 个：Chat / Session / Info / Model / Provider / Tool / Datasource /
│                         #   Workspace(+Member) / Market(+Audit,+My) / Governance / Approval /
│                         #   Trigger / Usage / Dashboard / Feedback / AuditLog / ApiKey /
│                         #   McpServer / Semantic / Attachment / Workflow(+Api,+Endpoint)
├── domain/               # 实体 + bo/vo（AgentInfo 等基础实体在 forge-agent-core）
├── event/  job/  mapper/ # 事件、定时任务、Mapper
└── service/
    ├── run/              # RunEventBuffer(SSE 游标重放) / RunStreamEmitter / RunTraceService /
    │                     #   RunCancelRegistry / TraceStepType
    ├── runner/           # AgentScopeRunner(ReAct 内核) / ToolAssembler / 各类工具：
    │                     #   CurrentTimeTool / HttpToolAdapter / McpAgentTool / SqlQueryTool /
    │                     #   SqlSchemaTool / DockerSandboxTool / SandboxCodeExecutor /
    │                     #   ReadAttachmentTool / RecallWorkingMemoryTool /
    │                     #   RunWorkingMemory / WorkingMemoryGuard / TokenBudgetHook /
    │                     #   ApprovalGateTool / OpenAiCompatRunner / MockAgentRunner
    ├── support/          # ChatContextAssembler / ChatRetrievalSupport / ChatStreamSupport /
    │                     #   ContextBudgetAllocator / SessionSummaryService /
    │                     #   AnswerCitationValidator / ChatRetrievalSupport.buildReferenceBlock /
    │                     #   WorkflowSchemaGuard
    ├── routing/ semantic/# IntentRouter / SemanticRouter / SemanticContextBuilder
    ├── workflow/         # WorkflowGraphValidator / WorkflowExpressionEngine（纯逻辑，可脱离 Spring 单测）
    ├── governance/       # AgentVersionService / PromptTemplateService / EvaluationService / ReleaseGateService
    └── impl/             # 各业务 Service 实现
```

## 运行链路要点

- **AgentScope 2.x ReAct 内核**：`AgentScopeRunner` 承担循环、流式事件与钩子；平台自研集中在资产（模型 / 工具 / 知识库）管理、隔离与治理。
- **上下文预算 L0**：`ContextBudgetAllocator` 按「系统提示词 / 记忆 / 检索块 / 工作记忆 / 历史」优先级瓜分 Token，历史有保底，系统提示词永远完整。系统提示词保持 KVCache 友好（动态时间由 `CurrentTimeTool` 工具调用获取，不写进提示词）。
- **L1 工作记忆**：`WorkingMemoryGuard` 是 `AgentTool` 装饰器，**注册层生效、各工具执行路径零改动**；工具结果可压缩进 Redis 便签 `agent:run:wm:{runId}:`，后续轮次用 `RecallWorkingMemoryTool` 取回。
  > AgentScope 2.x `ToolResultBlock.text()` 默认状态是 **RUNNING 不是 SUCCESS**，判「可压缩」必须接受 null / RUNNING / SUCCESS，否则 L1 全程不生效。
- **L2 滚动摘要**：`SessionSummaryService` 用 `agent_session.summary_text` + `summary_upto_msg_id` 水位 CAS 推进。
- **检索注入与问答可信度（R4）**：`ChatRetrievalSupport` 调用 RAG 并用 `buildReferenceBlock(...)` 生成结构化资料块（`ReferenceBlock` record，JSON 引用列表 + 合法编号清单，尖括号转 `\u003c` 防伪分隔符）；`AnswerCitationValidator` 在 `finishRound` 开头做硬门禁（只认 Markdown 文本节点 `[n]`，越界拒发：非流式抛错、流式发 error 终态不发 done），mock runner 跳过。
- **运行治理**：全链路 Run / Trace（阶段耗时、首 Token 延迟、Token 与状态）、SSE 断线续传（`RunEventBuffer` 游标）、跨实例取消令牌、聊天消息运行链路下钻、僵尸 running 定时兜底、轨迹快照截断。
- **编排画布 vs forge-workflow**：本模块的「Agent 编排画布」是面向一次 LLM 任务的自研 JSON DAG（试运行、版本快照、运行回放）；`forge-workflow`（WarmFlow）是面向 OA 审批的流程引擎。**两者零依赖、互不调用，不要混用**。

## 免登录与对外入口

| 入口 | 防线 |
| --- | --- |
| `/agent/trigger/webhook/**` | secret 鉴权 + IP 预限流 → 鉴权失败计数 → 端点限流；请求体 256KB 上限；投递幂等防重 |
| `/workflow-api/**` | 空间 API 密钥鉴权 + 每分钟限流 + 超时硬顶 + 幂等防重 |
| `/chat-api/**`、`/chat-channel/**`、`/chat-voice/**` | 客服域各自防线，见 `forge-customer-service` / `forge-callcenter` README |

`WorkflowGraphValidator` 静态校验 + `WorkflowExpressionEngine` 表达式求值都是可单测纯逻辑；Cron 调度线程只做投递，长执行交有界线程池；审批决策与过期清扫用带状态条件更新互斥。`WorkflowSchemaGuard` 在启动时自检工作流相关表结构并输出 WARN 指引。

## 配置

`agent.runtime.*`（ReAct 超时与迭代上限、语义路由、工作流节点超时、`workflow-http-guard-enabled` http 节点 SSRF 防护）、`agent.chat.*`（对话链路超时、预算与上下文参数，含 `context-window-tokens` / `history-*` / `summary-*` / `working-memory-*`）、`agent.resource-sharing.enabled`（资源域双模式总开关）、`spring.task.scheduling.pool.size=4`（隔离定时任务故障域）。

## 测试

**101 用例 / 15 类**，全部 `@Tag("dev")`：

```bash
mvn -Pdev test "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-agent -am
```

| 组 | 类（用例数） |
| --- | --- |
| 检索链路与引用门禁 25 | `ChatRetrievalSupportCrossKbTest`(6) / `ChatRetrievalSupportRelevanceTest`(5) / `ChatContextAssemblerLoadHistoryTest`(4) / `AnswerCitationValidatorTest`(6) / `ReferenceBlockBuilderTest`(4) |
| 表达式引擎 11 | `WorkflowExpressionEngineTest` |
| 图校验器 21 | `WorkflowGraphValidatorTest` |
| 资源域守卫 8 | `ResourceAccessGuardTest` |
| 记忆预算与工作记忆 8 | `ContextBudgetAllocatorTest`(5) / `WorkingMemoryGuardTest`(3) |
| MCP 空间归属 16 | `AgentMcpServerServiceImplTest`(11) / `AgentInfoServiceMcpBindingTest`(3) / `ChatContextAssemblerMcpVisibilityTest`(2) |
| 工作流执行体 8 | `AgentWorkflowGraphCharacterizationTest`（走 `trialRun` 公共入口 + `dryRun`，不依赖私有方法，拆分前后可复用） |
| 问数标识校验 4 | `SqlQueryServiceIdentifierTest` |

验收以 surefire 报告的 `tests/failures/errors` 为准，不认 EXIT=0。
