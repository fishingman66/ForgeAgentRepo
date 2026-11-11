# 智造 ForgeAgent 后端

后端采用单体多模块架构：一个进程承载全部业务，模块按领域内聚划分、依赖单向。技术栈为 Spring Boot 4.1 + JDK 21 + Sa-Token + MyBatis-Plus + MapStruct Plus，运行内核基于 AgentScope 2.0，存储为 MySQL 8 + Redis 6（向量库：生产 Milvus 2.4，本地开发可用 MySQL 向量列替代，见 [`forge-rag` README](forge-modules/forge-rag/README.md)）。

选择单体而非微服务的理由：当前阶段业务耦合在对话主链路上，拆分会把一次对话变成跨服务长事务；各模块已按边界隔离，后续出现独立扩缩容需求时再按模块平移。

## 模块说明

```
backend
├── forge-admin            # 启动模块（8080），配置文件在 src/main/resources
├── forge-api              # 系统 API（跨模块 DTO 与服务接口）
├── forge-common           # 公共设施（core / mybatis / redis / satoken / web / oss / json / excel 等）
├── forge-extend           # 独立附属进程（monitor-admin 监控 / snailjob-server 调度）
├── forge-modules
│   ├── forge-agent-core   # 共享内核：Agent / 模型 / 空间成员 / 附件实体与映射、权限助手、ResourceAccessGuard、配置缓存、模型工厂
│   ├── forge-agent        # Agent 平台业务：工作空间、编排、对话运行、市场、触发器、审计计量
│   │   └── service/workflow  # 图校验器 WorkflowGraphValidator / 表达式引擎 WorkflowExpressionEngine（纯逻辑，可脱离 Spring 单测）
│   ├── forge-rag          # 知识库检索增强：文档摄取、分块向量化、混合检索与重排、MCP 检索端点
│   ├── forge-memory       # 长期记忆：跨会话记忆沉淀、对话注入、异步抽取
│   ├── forge-system       # 系统管理
│   ├── forge-workflow     # 工作流（WarmFlow）
│   ├── forge-job          # 定时任务（SnailJob）
│   ├── forge-gen          # 代码生成
│   ├── forge-customer-service  # 智能客服域（C0~C4 文本 + A1~A5 坐席辅助）：见 [模块 README](forge-modules/forge-customer-service/README.md)
│   ├── forge-callcenter        # 语音呼叫中心（V1~V2）：见 [模块 README](forge-modules/forge-callcenter/README.md)
│   └── forge-callcenter-common # 零业务依赖契约层（V0.5，仅 JDK）：见 [模块 README](forge-modules/forge-callcenter-common/README.md)
├── script/sql             # 基座初始化 SQL（mysql/ 与 postgres/ 两组：forge_vue / forge_workflow / forge_job）
└── sql                    # Agent 平台初始化 SQL（agent_*.sql，含客服域 agent_cs_*.sql 与语音域 agent_cc_*.sql，MySQL 方言）
```

分层约定：`forge-agent-core` 沉淀被多个业务模块共享的实体与领域服务，`forge-agent` 只做平台编排，两者依赖方向单向；`forge-common` 不感知任何业务。客服域依赖方向严格单向：`forge-callcenter → forge-customer-service → forge-callcenter-common`（公共契约层编译期仅 JDK，出现任何业务模块依赖即视为破坏）。`ResourceAccessGuard`（agent-core）是工具 / 数据源 / 模型三类资源「平台共享 / 空间私有」双模式的唯一可见性入口，列表查询把 `(workspace_id IS NULL OR workspace_id IN (...))` 下推到 SQL，免登录链路以工作流归属 Agent 的空间作为 `callerWorkspaceId`。

### 客服域与呼叫中心要点

- **免登录三入口各自带防线**：`/chat-api/**`（渠道校验 + Origin 白名单 + HMAC 访客令牌 + IP/端点限流）、`/chat-channel/**`（渠道验签 + 时间窗 + nonce + MsgId 幂等）、`/chat-voice/**`（HMAC 验签 + 时间窗 + nonce + 事件轨迹唯一键幂等 + 死信轨迹）；回调响应固定 `{message, retcode}` 且 HTTP 200，不返回项目 `R<T>`，失败信息对外模糊化。
- **事件驱动**：文本与语音共用零依赖契约层的事件信封与通道接口（SSE 实现留在 customerservice）；语音 8 态状态机单调推进（乱序 / 迟到 CDR 不回退），事件指纹为跨语言对齐的 sha256 契约（Python 调试端同向量）。
- **业务绑定**：`CallBusinessBinder` 是通话与文本域关联的唯一写入口（ring 建会话、transfer 建工单入 ACD、挂机按合法跃迁结案，RESOLVED 自动发 CSAT）；转写质检与通话日指标在挂机钩子收敛。
- **录音存储抽象**：`RecordingStorage` 接口下 `oss`（sys_oss_config）与 `local-fs`（开发环境本地目录 + HMAC 签名下载 + 路径穿越防护）两个实现，`recording-store` 一个配置切换，对象键规则一致；留存清理与隐私擦除（Spring 事件 `CsConversationErasedEvent` 级联）都会删除录音对象并写审计。
- **开关纪律**：`agent.customer-service.enabled` 与 `agent.callcenter.enabled` 默认关闭；关闭时对应 Bean 与端点不装配，DDL 存在不影响启动。prod 下 `CallcenterProductionReadinessValidator` 拒绝 Mock 供应商与默认密钥。

### 坐席辅助（A1~A5）

文本消息与语音转写两条触发链汇聚到 `SeatAssistService.generate`，产出候选话术、意图标签与建议动作，推给坐席工作台：

- **A1 文本**：`CsVisitorMessageSavedEvent` 异步旁路，`buildItems` 纯函数（FAQ 优先 / top3 / 500 字截断 / 空候选不推）。
- **A2 语音**：ASR 定稿分片触发，判定链为 定稿 → 说话人黑名单 → 置信度（`assist.min-asr-confidence`）→ 会话已绑定 → 节流（`assist.throttle-seconds`，Redis `trySet`，按**通话**维度）。
- **A3 意图**：规则打标（KEYWORD / REGEX，按 priority 首个命中），未命中用最近 3 轮访客原话兜底；意图关联知识库并入检索范围，命中关键词的分片 `reweight` 加分。**未接 LLM 分类**，接入点是 `classify` 返回 `Match`。
- **A4 动作**：白名单 `REPLY` / `LINK` / `RESOLVE`，只执行推荐快照里生成过的动作，复用既有 `reply` / `transition` 端点并补受理人校验；「触发工作流」按低风险原则未开放。
- **A5 度量**：日指标 upsert + **采纳率按推荐条数**（采纳条数 ÷ 推荐条数，动作次数单列）+ 零采纳回流（同会话最近 3 条全未采纳 → 触发原文生成待审 FAQ）。看板打开即重算，无调度器。
- **协议纪律**：`SEAT_SUGGEST` payload 只增不改；`CALL_TRANSCRIPT` 只增 `text` / `speaker` / `isFinal`，正文沿入库前的 PII 脱敏结果。

### P0 增强（2026-11-10）

对标同类产品补齐六项基础能力（详见 `forge-customer-service/README.md`）：

- **关键词前置转人工**：`CsHandoffKeywords` 命中即建单，不跑 FAQ / 检索 / 模型；关键词可按渠道配置。
- **工单动作**：转派（校验目标坐席在线且未满）、退回工单池、取消转人工、驳回待确认，补了两条跃迁边。
- **SLA 扫描**：`CsSlaScheduler` 超时告警（1h 去重）+ 待确认自动确认 + 已解决自动关闭，可整体关闭。
- **敏感词**：`agent_cs_sensitive_word` 词库，BLOCK 入站转人工 / 出站换兜底，MASK 打码，审计 `SENSITIVE_HIT`。
- **交接摘要**：转人工后异步生成结构化摘要（意图/情绪/已尝试/待解决/下一步/建议开场），规则版，不依赖模型。
- **回流定时化**：A5 零采纳回流挂每日 2:30，`assist.recycle-cron` 可配。

### P1 增强（2026-11-10）

- **语义缓存**：三道闸门（意图白名单 / 6 位连续数字 / 空间×渠道分区），精确 + 编辑距离近似（0.95），流式与非流式双路径，只缓存出站过滤后的正常回答；默认关闭。
- **槽位填充**：表单配置驱动（默认退换/发票），自由文本整句赋值 + 正则抽取 + 首个必填追问，收齐转人工；默认关闭。
- **知识缺口**：零命中按问题归并、按次数升级优先级（≥5 次 P0），一键转待审答案。
- **badcase 三态**：采纳为知识 / 标记评测并导出 / 忽略；评测本身在平台侧，客服不重造引擎。
- **修掉一个启动级缺陷**：`@Scheduled(fixedDelayString)` 配 0 会让定时任务注册失败、应用起不来；改为独立开关 `sla-enabled`。

### P2 增强（2026-11-10，四项全交付）

- **会话级成本**：按客服会话 `agent_session_id` 归集 `agent_token_usage` 到日维度，给出「每会话 token」「每次解决 token」；金额只在配了单价时折算，未配返回 null 并标注——不写 0 冒充免费。
- **配额**：按空间当日 token 判定，WARN 放行 / BLOCK 拒绝并转人工（转人工不再消耗模型）+ `QUOTA_BLOCK` 审计；DEGRADE 未实现（需模型路由支持）。
- **访客附件**：免登录上传（`/chat-api/{code}/sessions/{cid}/attachments`，扩展名白名单 + 10MB + 归属校验），**不进入模型上下文**，只供坐席查看；纯附件消息直接转人工。
- **死信**：`agent_cs_dead_letter` 只收可重投的业务失败（摘要可重投、附件标不可重投），失败方发事件由死信服务落库，避免与摘要服务形成循环依赖；运营可重投或忽略。

### P3 增强（2026-11-10，多 Agent 路由与坐席 SSE）

- **意图路由**：`agent_cs_agent_route` 把意图映射到专家 Agent（退款走售后、物流走订单），渠道级优先于空间级、同级按 priority；**会话首条消息判定一次并锁定**（`agent_routed`），目标不存在 / 停用 / 跨空间一律回落渠道默认。
- **坐席实时流**：`GET /conversations/{cid}/stream?after=` 用 SSE 替代 2 秒轮询，复用 `RunEventBuffer` 游标重放，15 秒心跳，单连接 10 分钟到期由前端带游标重连；连续失败三次才退回轮询。

## 关键配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `agent.runtime.*` | 见 `AgentRuntimeProperties` | ReAct 超时与迭代上限、语义路由、工作流节点超时、`workflow-http-guard-enabled`（http 节点 SSRF 防护） |
| `agent.chat.*` | 见 `AgentChatProperties` | 对话链路超时、预算与上下文参数 |
| `agent.resource-sharing.enabled` | `true` | 关闭后所有资源强制按私有解释，供运维一键收紧（存量 NULL 资源需先迁移归属） |
| `spring.task.scheduling.pool.size` | `4` | `@Scheduled` 调度池，隔离取消观察器 / 审批清扫 / 僵尸 run 兜底的故障域 |
| `management.endpoints.web.exposure.include` | `health,info,metrics,loggers,threaddump,httpexchanges` | Actuator 已收口，`show-details: WHEN_AUTHORIZED` |
| `agent.customer-service.*` | 见 `CsModuleProperties` | 客服域开关、SSE 通道、访客令牌 TTL、限流、留存天数、PII 脱敏开关、渠道密钥主密钥与回调时间窗 |
| `agent.callcenter.*` | 见 `CallcenterProperties` | 呼叫中心开关、供应商（AICC_MOCK / AICC）、回调密钥与时间窗、录音存储（local-fs / oss）与留存天数、音频出域开关、D8 规模（坐席 / 并发 / 月分钟预算）、号码与中继实现 |
| `rag.knowledge.vector-store` | `milvus` | 向量存储实现：`milvus`（生产）/ `mysql`（本地开发，向量存分块表 BLOB + 应用层余弦；dev 配置已设为 mysql） |

## 构建与启动

```bash
# 构建（需 JDK 21 + Maven 3.9+）
mvn -Pdev clean package -DskipTests -T 1C
# 启动
java -Dserver.port=8080 -Dfile.encoding=UTF-8 -jar forge-admin/target/forge-admin.jar
```

配置加载顺序：`application.yml` → `application-{profile}.yml`，本地开发改 `application-dev.yml` 的 MySQL / Redis 连接即可。

## 模块测试

```bash
# forge-agent 模块：表达式引擎 11 + 图校验器 21 + 资源域可见性守卫 8 = 40 用例
mvn -Pdev test "-Dtest=ResourceAccessGuardTest,WorkflowExpressionEngineTest,WorkflowGraphValidatorTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" -pl forge-modules/forge-agent -am
# forge-rag 模块：MCP 检索端点协议一致性 15 用例
mvn -Pdev test "-Dtest=KnowledgeMcpEndpointTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dmaven.test.skip=false" -pl forge-modules/forge-rag -am
# 智能客服域：customerservice 19 类 = 74 用例（含 A1~A5、P0~P3 增强）
mvn -Pdev test "-Dtest=CsModulePropertiesTest,CsTicketStatusTest,CsChannelGuardTest,CsKnowledgeAnswerServiceTest,WechatMpChannelAdapterTest,CsSecretCipherTest,CsLocalePolicyTest,SeatAssistServiceTest,SeatAssistVoiceTest,SeatAssistActionsTest,CsIntentServiceTest,CsAssistMetricServiceTest,CsHandoffKeywordsTest,CsSensitiveWordServiceTest,CsSemanticCacheServiceTest,CsSlotFillingServiceTest,CsKnowledgeGapServiceTest,CsCostServiceTest,CsAgentRouteServiceTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" \
  -pl forge-modules/forge-customer-service -am
# 契约层：3 类 = 13 用例
mvn -Pdev test "-Dtest=CsContractConstantsTest,CsChannelSignatureTest,CsPiiMaskerTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" \
  -pl forge-modules/forge-callcenter-common -am
# 呼叫中心：网关 / 状态机 / 指纹 / 录音存储 / 业务绑定 5 类 = 19 用例
mvn -Pdev test "-Dtest=AiccGatewayTest,CallStateMachineTest,CallFingerprintsTest,LocalFsRecordingStorageTest,CallBusinessBinderTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" -pl forge-modules/forge-callcenter -am
```

三点注意：根 pom 默认 `maven.test.skip=true`（如上覆盖）；surefire 按 `@Tag` 过滤（客服域与呼叫中心的测试类均已标 `@Tag("dev")`，新增测试先加注解再写断言，否则被静默过滤呈现 EXIT=0 假绿）；PowerShell 下含点号的 `-D` 参数需加引号。`forge-agent` 模块的测试依赖为 `spring-boot-starter-test`（test scope），客服契约层与呼叫中心同款。验收以 `target/surefire-reports/TEST-*.xml` 的 `tests/failures/errors` 为准，不认 EXIT=0。

## 运行保障约定

- 免登录入口（`/agent/trigger/webhook/**`、`/workflow-api/**`）：IP 预限流 → 鉴权（失败计数）→ 端点级限流；请求体 256KB 上限；超时/中断均 `future.cancel(true)`；对外错误消息统一模糊化。
- 客服域免登录三入口（`/chat-api/**`、`/chat-channel/**`、`/chat-voice/**`）各自带完整防线（见「客服域与呼叫中心要点」）；语音回调对内部异常全捕获，死信落事件轨迹，绝不让 AICC 侧进入重试风暴。
- 工作流执行：`WorkflowGraphValidator` 静态校验 + `WorkflowExpressionEngine` 表达式求值（均为可单测纯逻辑）；Cron 调度线程只做投递，长执行交有界线程池；审批决策与过期清扫用带状态条件更新互斥；运行快照超长时裁剪轨迹 output，`varsJson` 保留全文以保审批恢复语义。
- 资源可见性：新增按 id 取用资源的链路，统一经 `ResourceAccessGuard`（登录态 `assertReadable/assertWritable`，免登录 `assertReadableBy`）；共享资源写权限仅超管，归属一经创建不可变。
- 客服域数据纪律：PII 脱敏在落库与进入模型前统一执行；删除类操作全部按明确 id / 时间条件下推 SQL、小批量幂等；审计事件（ERASE / RECORDING_* / CALL_CDR_RECONCILE）持续追加；语音域状态机单调推进，迟到事件只补字段不回退。
- 坐席辅助异步纪律：推荐两条触发链都是 `@Async` 旁路，检索耗时与异常不回传访客主链与语音回调；空候选不落库不推送；动作执行一律「先校验快照再动手」，且前端确认后才调端点——服务端不提供任何自动执行入口。

## 数据初始化

见根目录 README「快速开始」与「上线前必改清单」。Agent 平台表结构与菜单在 `sql/agent_*.sql`：

- **新库初始化**按根 README 的字母序通配导入即可；**存量库升级只执行新增脚本，不要重跑全量**——字母序不等于依赖序，重跑旧脚本会把增量修改冲掉（典型：重跑 `agent_mcp.sql` 会 drop 重建 `agent_approval` 并带回 `session_id NOT NULL`）；
- 新增脚本一律使用 information_schema 判断的幂等写法（存储过程模式参照 `sql/agent_g_series.sql`、`sql/agent_resource_scope.sql`）；客服域（`agent_cs_c0~c5.sql`）、坐席辅助（`agent_cs_a2/a3/a5.sql`，A4 无表变更）、P0~P3 增强（`agent_cs_p0/p1/p2/p2b/p3.sql`）、向量本地化（`agent_kb_local_vector.sql`）与语音域（`agent_cc_c0~c1.sql`）全部为幂等脚本，可重复执行；
- **存量库启用资源域双模式**：单独执行 `sql/agent_resource_scope.sql`（三表加 `workspace_id` 与索引，自带幂等过程，可重复运行），现有资源保持平台共享；新库已由 `deploy/docker/mysql/init/01-schema.sql` 直接带列；
- 应用启动时 `WorkflowSchemaGuard` 会自检工作流相关表结构，缺失时输出 WARN 与脚本路径指引。

## 协议

MIT，详见仓库根目录 [LICENSE](../LICENSE) 与 [THIRD-PARTY-NOTICES](../THIRD-PARTY-NOTICES.md)。
