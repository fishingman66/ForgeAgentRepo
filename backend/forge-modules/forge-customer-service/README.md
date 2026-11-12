# forge-customer-service · 智能客服域

文本客服（C0~C4）、坐席辅助（A1~A5）、运营增强（P0~P3）与知识图谱领域能力的完整实现：访客免登录接入、七态工单与 ACD、流式 SSE、CSAT / 质检 / FAQ 数据飞轮、合规擦除，以及基于对话内容与语音转写的坐席话术推荐、意图打标、建议动作与效果度量；另有客服域知识图谱维护页与检索词扩展。

模块开关 `agent.customer-service.enabled`（默认 `false`），关闭时全部 Bean 与端点不装配，主应用行为零变化。**92 个单元测试 / 22 类**。

## 包结构

```
com.forgeagent.customerservice
├── controller       # 9 个控制器：访客入口 / 渠道回调 / 坐席工作台 / 运营 / 坐席辅助 / 意图 / 图谱维护 / OpenAI 兼容 / 健康检查
├── service          # 工单、访客、渠道守卫、检索、质检、CSAT、指标、模板、坐席辅助、知识缺口、badcase、成本、配额、图谱维护与检索扩展
├── transport        # SseRealtimeTransport：RunEventBuffer 上的游标重放实现
├── channel          # 渠道适配（网页挂件 / 公众号 / 企微 / H5 / OpenAPI）
├── compliance       # PII 脱敏策略、多语种兜底、擦除与留存、密钥加解密、审计
├── domain / mapper  # 实体 + Mapper（表前缀 agent_cs_*）
├── event            # CsVisitorMessageSavedEvent（A1 推荐触发源）、擦除事件监听
└── config           # CsModuleProperties、条件装配、生产就绪校验
```

依赖方向：本模块依赖 `forge-callcenter-common`（事件信封 / 通道接口 / PII / 事件常量）与 `forge-kg`（图谱门面），并通过 `forge-agent` 的 `api.customerservice` 契约包单向依赖 Agent 执行域；**不可反向依赖 `forge-callcenter`**。跨模块入参一律用基本类型（如 `SeatAssistService.VoiceAssistInput`），避免循环依赖。

## 三类入口

| 入口 | 路径 | 防线 |
| --- | --- | --- |
| 访客免登录 | `/chat-api/{channelCode}/**` | 渠道校验 + Origin 白名单 + HMAC 访客令牌 + IP/端点限流 |
| 渠道回调 | `/chat-channel/{type}/{code}` | 渠道密钥验签 + 时间窗 + nonce + MsgId 幂等 |
| 坐席端 | `/agent/customerservice/**` | `@SaCheckLogin` + 坐席行 workspace 校验（`CsWorkspaceResolver`） |

坐席端 workspace 解析统一走 `CsWorkspaceResolver`：登录用户 → 坐席行 → workspace，坐席行缺失返回 `-1` 空空间（查不到任何业务数据，而不是抛异常把人挡在门外）。

## P0 增强（2026-11-10）

对标同类产品补齐的六项基础能力，脚本 `temp/p0_verify.py`（38/38）：

| 项 | 类 / 表 | 说明 |
| --- | --- | --- |
| 转人工关键词前置 | `CsHandoffKeywords`、渠道 `handoff_keywords` | 客户说「转人工」时直接建单，不跑 FAQ / 检索 / 模型 |
| 工单动作补全 | `CsTicketService.transferToAgent / transferToPool / cancelHandoff / reject` | 端点 `POST /tickets/{id}/transfer|cancel-handoff|reject`；转派要求目标坐席在线且未满 |
| SLA 扫描 | `CsSlaScheduler` | 超时写 `SLA_BREACH`（1h 去重）；待确认超时自动确认、已解决超时自动关闭，`sla-auto-advance=false` 只告警 |
| 敏感词拦截 | `CsSensitiveWordService` + `agent_cs_sensitive_word` | BLOCK 入站转人工 / 出站换兜底，MASK 打码，WARN 只审计；词库 60s 快照 |
| 交接摘要 | `CsHandoffSummaryService` + `agent_cs_handoff_summary` | 规则版结构化摘要，`GET /summaries?ticketId=`；LLM 版留 P1 |
| 回流定时化 | `CsAssistMetricService.recycleDaily` | 每日 2:30 扫所有有渠道的空间，`assist.recycle-cron` 可配 |

**两个既有缺陷在此次修掉**：`transition` 里 `setAssigneeId(null)` 因 `updateById` 忽略 null 从未生效（`reopen` 受影响）；状态机缺 `PROCESSING→WAITING_AGENT` 边（退回池依赖）。

## P1 增强（2026-11-10）

| 项 | 类 / 表 | 说明 |
| --- | --- | --- |
| 语义缓存 | `CsSemanticCacheService` + `agent_cs_semantic_cache` | 三道闸门（意图白名单 / 6 位连续数字 / 空间×渠道分区）；精确 hash + 编辑距离近似（0.95）；流式与非流式双路径；只缓存出站过滤后的正常回答；`semantic-cache-enabled` 默认关 |
| 槽位填充 | `CsSlotFillingService` + `agent_cs_slot_progress` + `slot-forms` | 自由文本整句赋值 / 正则槽位抽取 / 首个必填追问；收齐转人工；`slot-filling-enabled` 默认关 |
| 知识缺口 | `CsKnowledgeGapService` + `agent_cs_knowledge_gap` | 同问题归并累加，≥5 次 P0、≥3 次 P1；一键生成待审答案 |
| badcase | `CsBadcaseService` + `agent_cs_badcase` | 三出口：采纳为知识 / 标记评测并导出 / 忽略；来源：坐席忽略、零命中、敏感词拦截 |

**端点**：`GET/POST /knowledge-gaps`、`/knowledge-gaps/{id}/create-answer|ignore`、`GET /improvements`、`/improvements/{id}/adopt-kb|adopt-eval|ignore`、`GET /improvements/export-eval`、`GET/DELETE /semantic-cache`。

**修掉的缺陷**：`@Scheduled(fixedDelayString="${...sla-scan-ms:60000}")` 配 0 会让定时任务注册失败、应用起不来；改为独立开关 `sla-enabled`，`sla-scan-ms` 必须 >0。

## P2 增强（2026-11-10，四项全交付）

| 项 | 类 / 表 | 说明 |
| --- | --- | --- |
| 会话级成本 | `CsCostService` + `agent_cs_cost_daily` | 按客服会话 `agent_session_id` 归集 `agent_token_usage`；每会话 token / 每次解决 token；未配 `cost-per-1k-tokens` 时金额为 null 并标注说明 |
| 配额拦截 | `CsQuotaService` | 口径为空间当日 token（含该空间全部模型调用）；WARN 放行、BLOCK 拒绝并转人工 + `QUOTA_BLOCK` 审计；DEGRADE 未实现（需模型路由支持） |
| 访客附件 | `CsAttachmentService` + 平台 `agent_chat_attachment` | **附件不进模型上下文**：只落盘与关联会话，供坐席查看；纯附件消息直接转人工；扩展名白名单 + 10MB + 归属校验 |
| 死信 | `CsDeadLetterService` + `agent_cs_dead_letter` | 只收可重投的业务失败（摘要可重投、附件标不可重投）；失败方发事件、死信服务落库，避免循环依赖 |

**端点**：`GET /assist/cost?days=`、`POST /assist/cost/rebuild`、`GET /assist/quota`、`POST /chat-api/{code}/sessions/{cid}/attachments`（免登录）、`GET /conversations/{cid}/attachments[/{id}]`、`GET /dead-letters`、`POST /dead-letters/{id}/replay|ignore`。

## P3 增强（2026-11-10，多 Agent 路由与坐席 SSE）

| 项 | 类 / 表 | 说明 |
| --- | --- | --- |
| 多 Agent 意图路由 | `CsAgentRouteService` + `agent_cs_agent_route` | 意图 → 专家 Agent；渠道级优先、同级按 priority；**会话首条消息判定一次并锁定**（`agent_routed`），目标不存在 / 已停用 / 跨空间一律回落渠道默认 Agent |
| 坐席 SSE | `GET /conversations/{cid}/stream?after=` | 复用 `RunEventBuffer` 游标重放；15s 注释心跳；单连接最长 10 分钟，前端到期带游标重连；未登录直接 401（避免全局处理器往 event-stream 写 JSON） |
| OpenAI 兼容 | `POST /chat-api/{code}/v1/chat/completions` | 标准 OpenAI 请求/响应，支持 `stream=true`；会话优先 `?conversationId=`，其次按访客复用活跃会话，实际会话在 `system_fingerprint` 回带（`cs-conv-123`）；**失败一律用 HTTP 状态码 + OpenAI 错误结构**，不用项目内部的 200+code 约定 |

**为什么路由只在首条消息生效**：客服会话与 `agent_session` 一对一绑定，而 `ChatSendBo.agentId` 仅在 `sessionId` 为空时用于建会话。每轮换 Agent 等于每轮换会话、上下文全断——所以首个消息判定一次后锁定，`agent_routed=1` 之后的轮次直接复用。

**前端实时通道**：`ChatPanel` 优先用 `fetch + ReadableStream` 读 SSE（`EventSource` 带不了 `Authorization` 头，只能把 token 放 URL），连续三次连不上才退回 2 秒轮询。

## 知识图谱领域能力（P3 / M3~M4）

本模块是 `forge-kg` 的业务消费方之一（另两处：`forge-memory` 注入「关联背景」、`forge-rag` 图谱第三路）。图谱本体零业务依赖，客服侧负责空间隔离、维护入口、PII 打码与效果观测。

- **维护端点** `/agent/customerservice/knowledge/graph/*`：`GET /relations`（权重降序 + 冲突标注）、`GET /conflicts`（反义关系两两一组，审核=删错误侧）、`POST /relations`（手工登记三元组，幂等 upsert）、`DELETE /relations/{id}`（跨范围拒绝）、`GET /nodes`（空间实体 TOP）。范围由登录坐席所属空间解析（`CsWorkspaceResolver`），**不接收 workspaceId 参数**，防跨空间读写。前端维护页 `frontend/src/views/customerservice/KnowledgeGraph.vue`（菜单 40012）。
- **检索扩展**：`CsKgQueryExpander` 把访客口语命中的实体扩展成邻接实体词拼进知识检索 query（只扩检索词，不动 FAQ 匹配），开关 `agent.customer-service.kg-expand-enabled` 默认关、`kg-expand-max-terms` 默认 3；冲突词表可用 `kg.extra-conflict-pairs` 扩展客服语料。
- **效果指标**：`agent_cs_kg_expand_metric` 按日聚合 + `GET /expand-metrics` 端点 + 前端对比卡（扩展侧 vs 未扩展侧零命中率）。**这是观测口径，不是 A/B 实验**，不要当作因果结论。

> 维护数据落在「登录坐席所属空间」，与 Agent 所属空间不一定一致；清理测试数据要按坐席空间来。

## 坐席辅助（A1~A5）
推荐的两条触发链都汇聚到 `SeatAssistService.generate`，差别只在输入来源与前置判定：

| 期 | 触发 | 关键实现 |
| --- | --- | --- |
| A1 文本 | 访客消息落库后发布 `CsVisitorMessageSavedEvent` | `@Async` 旁路 + `buildItems` 纯函数（FAQ 优先 / top3 / 500 字截断 / 空候选不推） |
| A2 语音 | `CallEventDispatcher` 在 ASR 分片落库后调用 `suggestForVoice` | 定稿 → 说话人黑名单 → 置信度（`assist.min-asr-confidence`，默认 0.6）→ 会话已绑定 → 节流（`assist.throttle-seconds`，默认 10s，Redis `trySet`，按**通话**维度） |
| A3 意图 | 生成前打标 | `CsIntentService`：按 priority 首个命中（KEYWORD / REGEX）；未命中用 `composeQuery`（最近 3 轮访客原话 + 当前句）兜底；命中后 `kb_ids` 并入检索范围 + 命中关键词的分片 `reweight` 加分 |
| A4 动作 | 随推荐快照生成 | `SeatAssistActions` 白名单 `REPLY` / `LINK` / `RESOLVE`；`CsAssistActionService` 按快照 code 匹配 → 受理人校验 → 复用 `reply` / `transition` → 埋点 + 审计 |
| A5 度量 | 看板与回流任务 | `CsAssistMetricService`：日指标 upsert、**采纳率按推荐条数**、零采纳回流生成待审 FAQ |

**采纳率口径**（用户明确）：`adopted='1'` 的条数 ÷ 推荐总条数。一条推荐上先 REPLY 再 RESOLVE 仍算一条采纳；动作次数单列 `action_count`。日汇总用「总采纳 ÷ 总推荐」重算，不取各日均值。

**零采纳回流**：候选 = 超期未采纳 + 未回流 + 有 `trigger_text`；「连续零采纳」= 同会话最近 3 条推荐全部未采纳（不足 3 条不判，防新会话第一条被误伤）。命中后生成 `status=PENDING`、`source=seat_assist` 的待审答案（answer 留空等运营补），按 `question_hash` 去重，置 `recycled='1'` 只回流一次。

**协议纪律**：`SEAT_SUGGEST` 信封 payload 只增不改（`suggestionId` / `items` / `actions` / `source` / `intent` / `intentName`）；`CALL_TRANSCRIPT` 只增 `text` / `speaker` / `isFinal`，正文沿入库前的同一份 PII 脱敏结果。

## 主要端点

```
访客免登录
  POST   /chat-api/{code}/sessions                       建/复用会话，返回访客令牌
  POST   /chat-api/{code}/sessions/{cid}/messages        发消息（幂等 clientMsgId）
  POST   /chat-api/{code}/sessions/{cid}/messages/stream 逐字流式
  POST   /chat-api/{code}/sessions/{cid}/handoff         转人工
  GET    /chat-api/{code}/sessions/{cid}/events?after=   事件游标重放
  DELETE /chat-api/{code}/sessions/{cid}                 访客擦除（级联语音域）

坐席工作台
  GET    /agent/customerservice/tickets?status=          工单列表
  POST   /agent/customerservice/tickets/{id}/claim|reply|hold|resume|resolve|close|reopen
  POST   /agent/customerservice/tickets/{id}/transfer           转派(带 assigneeId)或退回池(不带)
  POST   /agent/customerservice/tickets/{id}/cancel-handoff     取消转人工回到 AI
  POST   /agent/customerservice/tickets/{id}/reject             待确认驳回退回处理中
  GET    /agent/customerservice/channels                 渠道管理

坐席辅助（A1~A5）
  GET    /agent/customerservice/suggestions?conversationId=      历史推荐
  POST   /agent/customerservice/suggestions/{id}/feedback        采纳/复制/忽略埋点
  POST   /agent/customerservice/suggestions/{id}/actions/{code}  执行 REPLY/LINK/RESOLVE
  GET    /agent/customerservice/conversations/{cid}/events?after= 坐席侧事件流（推荐/转写/通话）
  GET    /agent/customerservice/intents                          意图列表（平台级 + 本空间）
  POST   /agent/customerservice/intents                          新增/编辑（平台级只读）
  DELETE /agent/customerservice/intents/{id}
  GET    /agent/customerservice/assist/metrics?days=             辅助看板（日指标 + 汇总）
  POST   /agent/customerservice/assist/metrics/rebuild
  POST   /agent/customerservice/assist/recycle?hours=&limit=     零采纳回流
  GET    /agent/customerservice/assist/badcases?limit=           低质推荐追溯
  GET    /agent/customerservice/summaries?ticketId=              交接摘要(P0)

运营与合规
  GET/POST /agent/customerservice/knowledge-answers、/quality/rules、/quality/checks/{id}/review
  GET    /agent/customerservice/metrics、/csat、/csat/stats、/badcases
  GET    /agent/customerservice/compliance/audit
  POST   /agent/customerservice/compliance/erase/{cid}、/compliance/purge
  GET/POST/DELETE /agent/customerservice/sensitive-words         敏感词词库(P0)
  GET/POST /agent/customerservice/knowledge-gaps、/{id}/create-answer|ignore   知识缺口(P1)
  GET/POST /agent/customerservice/improvements、/{id}/adopt-kb|adopt-eval|ignore  badcase(P1)
  GET    /agent/customerservice/improvements/export-eval         导出评测样本(P1)
  GET/DELETE /agent/customerservice/semantic-cache?channelCode=  语义缓存查看/清空(P1)
  GET    /agent/customerservice/assist/cost?days=                会话成本(P2)
  POST   /agent/customerservice/assist/cost/rebuild
  GET    /agent/customerservice/assist/quota                    配额现状(P2)
  GET/POST/DELETE /agent/customerservice/agent-routes           意图→专家 Agent 路由(P3)
  GET    /agent/customerservice/conversations/{cid}/stream?after= 坐席 SSE 实时流(P3)
  POST   /chat-api/{code}/v1/chat/completions                   OpenAI 兼容入口(P3,免登录)

知识图谱(P3/M3~M4)
  GET    /agent/customerservice/knowledge/graph/relations      领域关系列表(带冲突标注)
  POST   /agent/customerservice/knowledge/graph/relations      手工登记三元组(幂等)
  DELETE /agent/customerservice/knowledge/graph/relations/{id} 删除关系(跨范围拒绝)
  GET    /agent/customerservice/knowledge/graph/conflicts      反义冲突盘点
  GET    /agent/customerservice/knowledge/graph/nodes          空间实体 TOP
  GET    /agent/customerservice/expand-metrics                 检索扩展效果日指标(观测口径)
```

## 数据表

| 域 | 表 |
| --- | --- |
| 渠道与访客 | `agent_cs_channel`、`agent_cs_visitor`、`agent_cs_conversation`、`agent_cs_message_receipt` |
| 工单与队列 | `agent_cs_ticket`、`agent_cs_ticket_event`、`agent_cs_queue`、`agent_cs_seat` |
| 运营 | `agent_cs_csat`、`agent_cs_quality_rule`、`agent_cs_quality_check`、`agent_cs_knowledge_answer`、`agent_cs_metric_daily` |
| 坐席辅助 | `agent_cs_seat_suggestion`（A1 推荐记录，A2 加 `source`/`call_id`，A5 加 `trigger_text`/`recycled`）、`agent_cs_intent`（A3）、`agent_cs_assist_metric_daily`（A5） |
| P0 增强 | `agent_cs_sensitive_word`（敏感词库）、`agent_cs_handoff_summary`（交接摘要）；渠道表加 `handoff_keywords` |
| P1 增强 | `agent_cs_semantic_cache`（语义缓存）、`agent_cs_slot_progress`（槽位进度）、`agent_cs_knowledge_gap`（知识缺口）、`agent_cs_badcase`（改进单） |
| P2 增强 | `agent_cs_cost_daily`（会话日成本）、`agent_cs_dead_letter`（死信）；附件复用平台 `agent_chat_attachment` |
| P3 增强 | `agent_cs_agent_route`（意图 → 专家 Agent）；会话表加 `agent_routed` |
| 合规 | `agent_cs_audit_log` |
| 图谱（M4） | `agent_cs_kg_expand_metric`（检索扩展日指标）；图谱本体 `kg_node` / `kg_edge` 属 `forge-kg`，菜单脚本 `agent_cs_kg_menu.sql` |

SQL 脚本（全部幂等，可重复执行）：`backend/sql/agent_cs_c0~c5.sql` + `agent_cs_a2.sql`（来源与通话追溯）、`agent_cs_a3.sql`（意图表 + 菜单 40010）、`agent_cs_a5.sql`（日指标表 + 回流列 + 菜单 40011）、`agent_cs_p0/p1/p2/p2b/p3.sql`（P0~P3 增强）、`agent_cs_kg_menu.sql`（图谱维护页菜单）。A4 无表结构变更。`forge_kg_core.sql` 提供图谱两表。

## 配置

`agent.customer-service.*`（`CsModuleProperties`）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 模块总开关 |
| `transport` | `sse` | 实时通道实现 |
| `visitor-token-ttl-seconds` | `1800` | 访客令牌有效期 |
| `channel-rate-limit-per-minute` | `60` | 渠道默认每分钟上限（Redis 原子计数） |
| `max-message-chars` | `4000` | 单条消息上限 |
| `retention-days` | `180` | 会话与消息留存天数 |
| `pii-mask-enabled` | `true` | 落库与进模型前统一脱敏 |
| `stream-enabled` | `true` | 逐字流式开关 |
| `channel-secret-key` | dev 占位 | 渠道密钥加密主密钥（生产必须覆盖） |
| `visitor-token-secret` | dev 占位 | 访客令牌 HMAC 密钥（生产必须覆盖） |
| `sla-first-response-ms` | `60000` | 首响 SLA 阈值 |
| `assist.throttle-seconds` | `10` | 语音推荐节流窗口（按通话） |
| `assist.min-asr-confidence` | `0.6` | 语音推荐最低 ASR 置信度（`null` 视为供应商未上报，放行） |
| `handoff-keywords` | `转人工,人工客服,...` | 转人工关键词（渠道 `handoff_keywords` 优先） |
| `sla-scan-ms` | `60000` | SLA 扫描间隔，`<=0` 关闭 |
| `sla-waiting-agent-minutes` / `sla-processing-minutes` | `10` / `120` | 两类超时告警阈值 |
| `sla-confirm-hours` / `sla-close-hours` | `24` / `72` | 待确认自动确认、已解决自动关闭 |
| `sla-auto-advance` | `true` | 关闭时 SLA 只告警不推进 |
| `sensitive-word-enabled` | `true` | 敏感词拦截开关（入站与出站） |
| `assist.recycle-cron` | `0 30 2 * * ?` | 零采纳回流定时表达式 |
| `sla-enabled` | `true` | SLA 扫描开关（关闭用它，不要配 `sla-scan-ms=0`） |
| `semantic-cache-enabled` | `false` | 语义缓存开关 |
| `semantic-cache-intents` | `RETURN_REFUND,INVOICE` | 可缓存意图白名单 |
| `semantic-cache-threshold` | `0.95` | 近似命中阈值 |
| `slot-filling-enabled` | `false` | 槽位填充开关 |
| `slot-forms` | 退换/发票两张 | 表单定义（key 与意图编码同名触发） |
| `quota-enabled` / `quota-daily-token-limit` | `false` / `0` | 配额开关与空间当日 token 上限（0=不限） |
| `quota-action` | `WARN` | 超额处置：WARN / BLOCK |
| `cost-per-1k-tokens` | `0` | 参考单价（元/千 token），0 表示不折算金额 |
| `kg-expand-enabled` | `false` | 图谱检索词扩展开关 |
| `kg-expand-max-terms` | `3` | 单次扩展的最大邻接实体词数 |

图谱本体配置在 `kg.*`（总开关 / 后端 / 扫描上限 / `extra-conflict-pairs` 冲突词表扩展），见 [forge-kg/README](../forge-kg/README.md)。

## 测试

22 类 **92 用例**，全部标 `@Tag("dev")`：

```bash
mvn -Pdev test \
  "-Dtest=CsModulePropertiesTest,CsTicketStatusTest,CsChannelGuardTest,CsKnowledgeAnswerServiceTest,WechatMpChannelAdapterTest,CsSecretCipherTest,CsLocalePolicyTest,SeatAssistServiceTest,SeatAssistVoiceTest,SeatAssistActionsTest,CsIntentServiceTest,CsAssistMetricServiceTest,CsHandoffKeywordsTest,CsSensitiveWordServiceTest,CsSemanticCacheServiceTest,CsSlotFillingServiceTest,CsKnowledgeGapServiceTest,CsCostServiceTest,CsAgentRouteServiceTest,CsKnowledgeGraphServiceTest,CsKgQueryExpanderTest,CsKgExpandMetricServiceTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" \
  -pl forge-modules/forge-customer-service -am
```

- 坐席辅助相关：`SeatAssistServiceTest`（候选组装 4）、`SeatAssistVoiceTest`（语音判定 5）、`SeatAssistActionsTest`（动作生成 6）、`CsIntentServiceTest`（打标与重排 9）、`CsAssistMetricServiceTest`（连续零采纳 + 采纳率 5）。
- P0 相关：`CsHandoffKeywordsTest`（关键词判定 5）、`CsSensitiveWordServiceTest`（级别与打码 4）。
- P1 相关：`CsSemanticCacheServiceTest`（三道闸门与相似度 5）、`CsSlotFillingServiceTest`（槽位抽取 3）、`CsKnowledgeGapServiceTest`（优先级 1）。
- P2 相关：`CsCostServiceTest`（金额换算与缺价口径 3）。
- P3 相关：`CsAgentRouteServiceTest`（路由匹配 5）。
- 图谱相关：`CsKnowledgeGraphServiceTest`（维护与范围隔离 7）、`CsKgQueryExpanderTest`（实体扩展 7）、`CsKgExpandMetricServiceTest`（日指标 4）。
- 基础与渠道相关：`WechatMpChannelAdapterTest`(3)、`CsLocalePolicyTest`(3)、`CsSecretCipherTest`(3)、`CsModulePropertiesTest`(2)、`CsTicketStatusTest`(3)、`CsChannelGuardTest`(3)、`CsKnowledgeAnswerServiceTest`(2)，共 19。

以上分组合计 **92 用例**（22 类）。
- 新增测试必须加 `@Tag("dev")`：根 pom `maven.test.skip=true` + surefire `<groups>${profiles.active}</groups>`，缺注解会被静默过滤（EXIT=0 但 0 tests）。还必须同时写 `-DskipTests=false`，只覆盖前者 test-compile 与执行都会被跳过。
- 验收以 `target/surefire-reports/TEST-*.xml` 的 `tests/failures/errors` 为准，不认 EXIT=0。

## 端到端验证脚本

`temp/a1_verify.py` ~ `temp/a5_verify.py`（25/25、29/29、32/32、32/32、34/34），每份自带编译、打包、jar 冒烟、造数、断言与清理。跑 e2e 时的三个固定坑：

1. 访客消息令牌走 `X-Visitor-Token` 头，不是 `Bearer`；
2. 渠道 `cors_allowlist` 要求请求带 `Origin`；
3. **推荐落库的硬前提是候选项非空**——没命中 FAQ 且 Milvus 不可用时不推，脚本必须先种 FAQ；A4 起还要先 `claim` 才能执行动作。

## 扩展点

- **LLM 意图分类**：替换 `CsIntentService.classify` 的返回（`Match`），调用方零改动；当前只做规则档。
- **触发工作流动作**：`SeatAssistActions` 加白名单项 + `CsAssistActionService` 加一个分支，鉴权沿用目标工作流端点；当前按「先只开放低风险动作」未开放。
- **回流定时化**：项目无调度框架，`POST /assist/recycle` 即入口，接入调度器直接调用。
- **新增渠道**：实现 `channel` 包下的适配接口并在 `CsChannelDispatchService` 注册，回调防线由 `CsChannelGuard` 统一承担。
