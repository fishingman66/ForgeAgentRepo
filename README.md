# 智造 ForgeAgent · 企业级智能体平台

把一个 AI 助手从想法推进到业务落地，中间隔着模型接入、提示词调试、知识库准备、工具对接、权限隔离、上线运营一整条链路。智造 ForgeAgent 把这条链路装进一个平台：以**工作空间**为资源与数据隔离边界，团队在一个系统里完成智能体的开发、运行、分发与治理。

## 它解决什么问题

- **模型散、入口乱**：统一管理模型供应商与模型配置，业务按需选用，不改代码即可切换
- **知识不进对话**：文档上传、解析、分块、向量化全流程内置，对话自动携带知识库检索结果
- **能力难复用**：编排好的智能体发布到市场，其他团队安装即用，避免重复建设
- **运行不透明**：Token 用量、审计日志、用户反馈全链路可查，成本与质量都有据可依

## 核心能力

| 领域 | 说明 |
| --- | --- |
| 智能体开发 | 可视化编排（DAG 校验、试运行、版本快照与回滚）、ReAct 内核驱动、结构化输出、多模态图片输入 |
| 知识库 | 文档摄取硬化（解析超时熔断、zip 炸弹/恶意文件拦截、扫描件识别与明确报错）、清洗层（页眉页脚/页码检测、NFKC、正则替换、PII 脱敏、空白归一，规则按库配置且参与指纹）、分块 token/字符双计价 × 结构感知/定长双策略、父子分块与偏移定位、向量 + 全文关键词混合检索（跨库 RRF 融合）、重排序双路由（Reranker API / LLM 打分，1500ms 硬超时回退）、索引代次化（重建期间旧索引照常可查）、向量写失败定时补偿（不再让文档整单失败）；向量库可切 Milvus（生产）与 MySQL 向量列（本地开发，不装 Milvus 也能跑通全链路） |
| 知识库治理 | 配置指纹（解析/分块双指纹）：改配置只标脏不自动重建，`reindex-stale` 差异重建 + **解析产物复用**（改分块参数只重切不重解析）；证据标注评测（span 覆盖判定，跨块区间并集防低估，阈值 0.5）+ 五指标（HitRate / Recall / Precision / MRR / NDCG@K + Wilson95% 置信区间仅展示）；双跑对比与重建门禁（ε 容差三态判决 PASSED / BLOCKED / LOG_ONLY，语料指纹不可比自动降级为仅记录）；零命中 → 评测用例 → 质量问题闭环（质量问题 3 态流转、复发自动 reopen、调试页一键生成用例）；**文档级 ACL**（文档 visibility + 角色授权，检索前下推到全文 SQL 与向量 filter，绝不召回后过滤，缓存按可见性隔离）+ **内容治理**（上传需审核 → 待审/发布/驳回、生效与过期窗口、回收站软删与 30 天清理，治理动作 O(1) 不碰向量库） |
| 问答可信度 | 对话检索注入结构化资料块（JSON 引用列表 + 合法编号清单写进 prompt，尖括号统一转义防伪分隔符/伪指令）；**引用校验硬门禁**（只认 Markdown 文本节点的 `[n]`，越界拒绝——非流式抛错、流式发 error 终态不发 done，不改写不裁剪）；库级零召回兜底开关（默认允许通用知识回答须声明；严格模式一律拒答，金融/医疗场景）；答案层五维 LLM 裁判接 agent_eval（correctness / faithfulness / completeness / citation_correctness / refusal_correctness，均分 ≥4 通过，只跑有参考答案或期望拒答的用例） |
| 对话运行 | SSE 流式输出、Token 预算双重拦截、多轮查询改写、回答引用溯源、消息重新生成与反馈 |
| 协作与分发 | 智能体市场（安装 / 评分 / 审核流）、团队接力（多智能体逐棒流式）、长期记忆沉淀与注入 |
| 集成 | MCP 服务器管理与工具审批、HTTP 扩展节点、Docker 沙箱代码执行、触发器（Cron / Webhook，投递幂等防重） |
| 工作流引擎 | 12 类节点（含并行分支 + 确定性 JOIN、人工审批 + 超时清扫）、失败策略三分支（终止 / 继续 / 跳转）与节点级重试超时、dryRun / 指定起跑 / 步数保险、模板深取 `{{a.b[0].c}}`、试运行异步化 + 逐节点事件流、触发器与对外 API 双通道执行（幂等防重） |
| 工作流 × 对话 | Agent 可配置工作流触发模式（关闭 / 每轮自动执行 / 按意图路由），对话由工作流执行链作答，节点进度事件实时推送 |
| 对外集成 | 工作流 API 端点（免登录、空间 API 密钥鉴权、每分钟限流、超时硬顶、幂等防重） |
| 运行治理 | 全链路 Run/Trace（阶段耗时、首 Token 延迟、Token 与状态）、SSE 断线续传、跨实例取消令牌、聊天消息运行链路下钻 |
| 配置治理 | Agent 版本快照与回滚、Prompt 模板三态绑定（手动 / 锁定版本 / 跟随稳定版）、效果评测与黄金基线、发布门禁与豁免审批 |
| 执行内核 | 结构化意图路由与本轮工具授权、四种协作模式（顺序 / 真并行 / 末棒复核 / 主控规划）、成员可带工具、分层长期记忆（事实去重、时间衰减、Token 预算） |
| 治理 | 工作空间成员与角色、API Key（SHA-256 存储）、审计日志、Token 用量统计、运营看板 |
| 资源域 | 工具 / 数据源 / 模型支持「平台共享」与「空间私有」双模式：共享资源全空间可用（仅超管可建改），私有资源仅归属空间可见；存量资源默认继续共享，可逐条调整 |
| 运行保障 | 免登录入口三层限流（IP 预限流 / 端点限流 / 鉴权失败计数）、http 节点 SSRF 防护、请求体 256KB 上限、审批竞态条件更新、Cron 调度异步化、僵尸 running 定时兜底、轨迹快照截断、健康检查探 DB/Redis 依赖 |
| 智能客服 | 访客免登录（HMAC 令牌）与多渠道接入（网页挂件 / H5 / 公众号 / 企微 / OpenAPI / OpenAI 兼容端点）、七态工单与 ACD 排队分配、转人工关键词前置与工单转派/退回池/驳回、SLA 超时告警与自动确认关闭、逐字流式 SSE（断线续传）与坐席实时事件流、按意图路由到专家 Agent、语义缓存（三道闸门，只缓存通用问答）、多轮槽位收集、访客附件（坐席查看，不进模型上下文）、会话级成本口径与 token 配额、CSAT / 质检 / FAQ 数据飞轮、知识缺口与 badcase 改进闭环、敏感词拦截（BLOCK/MASK/WARN）与 PII 脱敏、坐席交接摘要、死信重投、留存擦除审计、渠道密钥加密、多语言兜底；独立开关 `agent.customer-service.enabled`，关闭时主应用零变化 |
| 语音呼叫中心 | AICC 回调接入（HMAC 验签 + 时间窗 + nonce + 事件幂等）、8 态呼叫状态机（乱序不回退）、CDR / 转写 / 录音入库与签名访问、通话↔会话↔工单业务打通、ACD 自动分配与挂机结案自动 CSAT、转写质检、通话日指标、擦除级联语音数据；录音存储 `local-fs`（开发，无真实 OSS 也可跑通）/ `oss`（生产）一个配置切换；独立开关 `agent.callcenter.enabled`，默认关闭 |
| 坐席辅助 | 文本消息与语音转写双触发的话术推荐（异步旁路，不影响主链）、规则意图打标与检索加权、白名单建议动作（回复 / 发链接 / 标记解决，坐席确认后执行）、采纳率与零采纳回流看板（**采纳率按推荐条数**，连续未采纳自动生成待审 FAQ）；沿用客服域与呼叫中心开关 |
| 长期记忆体系 | 六层记忆（L0 预算分配 / L1 工作记忆 / L2 滚动摘要 / L3 语义检索 / L4 归档 / L5 治理），详见「长期记忆体系与知识图谱」小节；注入预算层层设防，治理默认关闭，升级零行为变更 |
| 知识图谱 | 独立模块 `forge-kg`（零业务依赖、存储后端可插拔）：记忆与客服两个域共用，实体关系抽取落图、幂等 upsert、1 跳邻接召回、反义冲突标注、MySQL 两表存图（生产可接 Neo4j，业务零改动） |

## 资源域模型（平台共享 / 空间私有）

工具、数据源、模型三类资源各自带归属空间。`workspace_id` 为空即平台共享，非空即空间私有，同一个平台里两种资源可以并存。共享资源含连接凭据与 API 密钥，只有超级管理员能创建和修改。空间开发者创建资源时选择归属空间，列表、详情、Agent 绑定、对话、工作流、问数、对外 API 与触发器所有链路统一经 `ResourceAccessGuard` 校验可见性。

升级存量库只需执行 `backend/sql/agent_resource_scope.sql`（幂等，可重复运行），现有资源自动保持平台共享，行为不变。配置 `agent.resource-sharing.enabled=false` 可一键收紧为全私有解释（此时存量共享资源需由运维迁移归属后再开放）。引用了空间私有资源的 Agent 禁止发布到市场。

## 智能客服与语音呼叫中心

平台内置完整的智能客服域（`forge-customer-service`）与语音呼叫中心（`forge-callcenter`，基于 AICC 对接），两者共享零业务依赖的契约层（`forge-callcenter-common`），依赖方向严格单向：`callcenter → customerservice → callcenter-common`。

**文本客服（已交付）**：访客免登录链路（`/chat-api/**`，渠道校验 + Origin 白名单 + HMAC 访客令牌）、渠道回调（`/chat-channel/**`，验签 + 时间窗 + nonce）、七态工单与 ACD（技能匹配 → 最小负载 → 入队时间）、坐席工作台、逐字流式 SSE（复用全局 RunEventBuffer，断线续传）、CSAT 满意度、可配置质检规则、FAQ 数据飞轮（零命中回收 → 审核 → 生效）、每日通话外指标看板、PII 脱敏（手机号 / 身份证 / 银行卡 / 邮箱）、留存到期清理与隐私擦除（级联客服域全量数据）、渠道密钥 AES-GCM 加密、中英文兜底话术。

**语音呼叫中心（已交付，AICC Mock 链路）**：

- **适配层**：`POST /chat-voice/{provider}/{code}` 免登录回调（固定返回 `{message, retcode}` + HTTP 200，不返回项目 `R<T>`）；供应商差异全部收口在 `CsVoiceGateway` 接口，换真实供应商只换实现
- **状态机**：8 态呼叫状态（RINGING → … → ENDED）单调推进，事件轨迹唯一键 `(call_id, event_seq, event_type)` 幂等，乱序与迟到 CDR 不回退状态；异常事件落 `dead.*` 死信轨迹
- **业务打通**：一通电话自动映射客服会话与工单（ring 建会话、transfer 建工单入 ACD 队列、坐席就绪触发自动分配、挂机按合法跃迁结案），结案自动发出 CSAT 邀请，挂机后转写自动质检（`VOICE_TRANSCRIPT`）、通话日指标幂等重算
- **录音与合规**：`call.record.ready` 一次性取回录音入存储，只存对象键不存云侧 URL；坐席访问走短期签名 URL 并写 `RECORDING_ACCESS` 审计；留存到期清理与隐私擦除均级联删除录音对象并留审计
- **存储可切换**：`agent.callcenter.recording-store` 取 `local-fs`（开发环境，本地目录 + HMAC 签名下载，无需真实 OSS）或 `oss`（生产，走 `sys_oss_config`），对象键规则一致、业务代码零改动
- **扩展接口**：`NumberProvider` / `TrunkProvider`（D8 暂为 noop 实现）；真实 AICC 接入（V0-real）时替换 `AiccGateway` 的 verify / parse / fetchRecording 三个方法即可，业务层零改动

**配置开关**（默认全关，开启不影响平台既有功能）：`agent.customer-service.enabled`、`agent.callcenter.enabled`；语音回调渠道为双表同码并存（`agent_cs_channel` 文本域渠道 + `agent_cc_channel_config` 回调验签渠道），缺一不可。

**坐席辅助（已交付，A1~A5）**：
- **双触发**：文本消息落库后异步出推荐（A1）；语音 ASR 定稿分片经 定稿 → 说话人 → 置信度 → 会话绑定 → 节流 五道判定后触发（A2，节流按通话维度，Redis 实现）
- **意图**：规则打标（关键词 / 正则，按优先级首个命中），未命中用最近 3 轮访客原话兜底；命中后并入关联知识库范围并对命中内容加权（A3）
- **动作**：白名单 `REPLY` / `LINK` / `RESOLVE`，只执行推荐快照里生成过的动作，复用既有 `reply` / `transition` 端点并补受理人校验，坐席确认后才执行；「触发工作流」按低风险原则未开放（A4）
- **度量**：推荐条数口径的采纳率（一条推荐多次动作只算一条采纳）、意图覆盖、动作执行与回流量；零采纳回流把「同会话最近 3 条全未采纳」的推荐原文生成待审 FAQ，运营补答案后审核生效（A5）
- **可见性**：坐席工作台的会话面板消费事件流（`cs_seat_suggest` 出卡片、`call.transcript` 出转写），运营侧新增「意图管理」与「辅助看板」两页

**SQL**：`backend/sql/agent_cs_c0~c5.sql`（文本域）、`agent_cs_a2/a3/a5.sql`（坐席辅助，A4 无表变更）、`agent_cs_p0/p1/p2/p2b/p3.sql`（P0~P3 增强）、`agent_kb_local_vector.sql`（本地向量列）与 `agent_cc_c0~c1.sql`（语音域）均为幂等增量脚本，可重复执行；知识库/RAG 线为 `agent_kb_r1_retrieval.sql`（检索日志与阈值口径）、`agent_kb_r2_chunking.sql`（分块单位/策略）、`agent_kb_r2_clean.sql`（清洗规则）、`agent_kb_r5_fingerprint.sql`（配置指纹与解析产物）、`agent_kb_r3_eval.sql`（证据评测与门禁列）、`agent_kb_r3_quality.sql`（质量问题闭环表）、`agent_kb_r4_trust.sql`（零召回兜底/严格拒答 + 期望拒答用例，幂等）、`agent_kb_r5_acl.sql`（文档 ACL 与内容治理，幂等）；长期记忆与知识图谱的脚本见下一节「相关 SQL」。详细方案文档见 `docs/`（检索内核、摄取硬化、评测闭环均有对应章节与交付记录）。

## 长期记忆体系与知识图谱

平台把「记住用户」做成六层体系，全部落 MySQL、零新基建，默认关闭时主链路行为与未升级完全一致（dev 配置已打开便于验证）。

**六层能力**：

- **L0 预算分配**（`ContextBudgetAllocator`）：系统提示词 / 记忆 / 检索块 / 工作记忆 / 历史按优先级瓜分上下文 Token 预算，历史部分设保底，预算不足时按优先级逐层裁剪，系统提示词永远完整
- **L1 工作记忆**：Redis 运行级便签（`RunWorkingMemory`），`AgentTool` 装饰器注册层生效、各工具执行路径零改动，工具结果可压缩进便签供后续轮次引用
- **L2 滚动摘要**：`agent_session.summary_text` + `summary_upto_msg_id` 水位 CAS 推进，长会话自动摘要成注入块，最近消息保持原文
- **L3 语义检索**：`agent_memory` 内嵌向量列（float32 小端 BLOB + 应用层余弦，扫描上限 5000），复用 RAG 的 `EmbeddingFactory`（`local-hash` 兜底 / OpenAI 兼容模型）与 `VectorCodec`；`memory.vector.backend` 只认 `mysql`，其余取值降级告警一次（同值去重）；召回融合公式 `0.6×语义相似度 + 0.4×现状评分`，事实键去重；写入侧异步向量化 + 每轮补偿扫描双保险
- **L4 归档**：`agent_message_archive` 同构归档表，会话最后消息超 `archive-retention-days`(180) 天且未置顶的消息分批转移（INSERT...SELECT + 按归档表 id 对账删主表），保留原 id 可回溯
- **L5 治理**：用户级遗忘（`DELETE /agent/memory/user/{userId}/forget?confirm=FORGET`，级联删除 记忆含向量 → 图谱 → 归档表，三层零残留）、容量配额（`agent_info.memory_quota` + 全局默认，抽取写入前先淘汰低分、定时盘点，淘汰进归档不物理删）、TTL（`expire_at` 注入与召回双过滤 + 治理任务归档）；治理开关 `memory.governance.enabled` 默认关闭，`POST /agent/memory/governance/run` 可手动触发

**注入路径**：召回（评分 + 语义融合）→ 预算裁剪 → 稳定事实条目（factKey 排序，KVCache 友好）→ 尾部「关联背景」（图谱 1 跳邻接 + 说法相反标注）。`GET /agent/memory/preview` 可预览与真实注入一致的记忆块，排查「召回了什么、被裁了什么」不用靠猜。

**管理页**：「智能体搭建 → 长期记忆」——记忆列表（分页 / 语义检索）、记忆图谱（实体与关系边、存量补建）、治理与遗忘（注入预览 / 遗忘 / 手动治理）。

**知识图谱（`forge-kg` 独立模块）**：

- **范围通用**：`KgScope(scope_type, scope_id, user_id)` 三元组——记忆用 `agent`，客服领域用 `workspace`（共享级 `user_id=0`）；模块只依赖 common 层，`memory → kg`、`customer-service → kg`，两域互不依赖
- **后端可插拔**：`KgStore` SPI + `KgService` 门面，MySQL 两表（`kg_node` / `kg_edge`，唯一键幂等 upsert、salience/weight 累加）为默认实现；生产换 Neo4j 只需新增 `backend()="neo4j"` 的实现 Bean，调用方零改动；配了未实现的后端按关闭处理并告警一次
- **客服领域能力**：维护端点 `/agent/customerservice/knowledge/graph/*`（关系登记 / 冲突盘点 / 实体盘点，坐席登录态按所属空间隔离，维护页「智能客服 → 知识图谱」）；反义关系词表冲突判定 + `kg.extra-conflict-pairs` 配置扩展
- **图谱辅助检索**：`CsKgQueryExpander` 把访客口语扩展成邻接实体词拼进知识检索 query（开关 `agent.customer-service.kg-expand-enabled`，默认关闭）；效果指标 `agent_cs_kg_expand_metric` 日聚合 + `/expand-metrics` 端点 + 维护页对比卡（扩展侧 vs 未扩展侧零命中率，观测口径非 A/B）

**配置索引**：`memory.vector.*`（向量开关/后端/模型/阈值）、`memory.graph.*`（注入预算/补建批量）、`memory.governance.*`（治理开关/归档保留天数/配额/TTL）、`kg.*`（图谱总开关/后端/扫描上限/冲突词表扩展）、`agent.customer-service.kg-expand-enabled`。

**相关 SQL**：`agent_p2_memory.sql`（记忆主表）、`agent_p3_m1_session.sql`（会话摘要水位）、`agent_p3_m2_memory_vector.sql`（向量三列）、`forge_kg_core.sql`（图谱两表）、`agent_p3_m4_memory_governance.sql`（归档两表 + expire_at + memory_quota + 指标表）、`agent_cs_kg_menu.sql`（客服图谱菜单）。

## 设计决策

这些取舍记录在此，供评估与二次开发参考：

- **单体多模块，而非微服务**。当前阶段交付速度优先，模块边界（`forge-modules/*`）按业务内聚划分，依赖单向；待某模块出现独立扩缩容需求时再拆，避免过早分布式化。
- **运行内核基于 AgentScope 2.0**。ReAct 循环、流式事件、钩子机制由内核承担，平台自研力量集中在资产（模型 / 工具 / 知识库）管理、隔离与治理层。
- **系统提示词保持 KVCache 友好**。动态内容（如当前时间）不写入系统提示词，避免破坏模型供应商的前缀缓存；时区等恒定约定进系统提示词，实时数据约定由工具调用获取。
- **混合检索优于单一向量检索**。关键词命中与向量召回经 RRF 融合，配合父子分块与可配置重排，在低资源文档场景下比纯向量方案更稳。
- **代码执行默认隔离**。沙箱以一次性容器运行，内存 / CPU / 网络 / 超时全部硬约束。
- **界面交互遵循「内容为主、操作随行」**。消息气泡只承载内容，操作工具栏常驻气泡外侧，避免视觉噪音（详见聊天页实现）。
- **两套工作流各司其职**。「Agent 编排画布」是面向一次 LLM 任务的自研 JSON DAG（试运行、版本快照、运行回放）；`forge-workflow`（warm-flow）是面向 OA 审批的流程引擎。两者零依赖、互不调用，页面与文档中做了命名区分，不要混用。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3 + TypeScript + Element Plus + Vite + Pinia + pnpm |
| 后端 | Spring Boot 4.1 + JDK 21 + Sa-Token + MyBatis-Plus + MapStruct Plus |
| 运行内核 | AgentScope 2.0 |
| 存储 | MySQL 8+（utf8mb4；无 Milvus 时可用 MySQL 9.7 社区版 VECTOR 存向量，规模几千条内够用）+ Redis 6（Redisson）+ Milvus 2.4（可选，知识库向量化） |

## 快速开始

### 方式一：Docker Compose 一键部署

```bash
cd deploy/docker
cp .env.example .env
# 按需修改 .env 中的密码等配置
docker compose up -d
# 访问 http://localhost:80
```

默认管理员：`admin / admin123`

### 方式二：本地开发

**1. 环境**：JDK 21、Maven 3.9+、Node 18+ 与 pnpm；MySQL 8、Redis 6 必装，Milvus 2.4 可选（不部署仅影响知识库检索，平台其余功能不受影响）。

**2. 初始化数据库**（创建 `forge-vue` 与 `forge-job`，均 utf8mb4）：

```sql
CREATE DATABASE `forge-vue` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE `forge-job` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

```bash
mysql --default-character-set=utf8mb4 -u用户名 -p forge-vue < backend/script/sql/mysql/forge_vue.sql
mysql --default-character-set=utf8mb4 -u用户名 -p forge-vue < backend/script/sql/mysql/forge_workflow.sql
mysql --default-character-set=utf8mb4 -u用户名 -p forge-job < backend/script/sql/mysql/forge_job.sql
# Agent 平台表结构与菜单
for f in backend/sql/agent_*.sql; do mysql --default-character-set=utf8mb4 -u用户名 -p forge-vue < "$f"; done
```

> 导入务必带 `--default-character-set=utf8mb4`，否则中文会被双重编码。
>
> **增量脚本顺序注意事项**：`backend/sql/agent_*.sql` 按字母序导入不等于依赖序，重复导入旧脚本会把新脚本的表结构改回去。
> 典型案例：重跑 `agent_mcp.sql` 会 drop 并重建 `agent_approval`，把 `agent_step5.sql` 已放开的 `session_id` 非空约束带回来，
> 导致含人工审批节点的工作流执行失败。升级存量库时只执行新增脚本，不要重跑全量；应用启动时 `WorkflowSchemaGuard`
> 会自检工作流相关表结构并在缺失时输出 WARN 与脚本路径指引。
>
> 资源域双模式升级：存量库单独执行 `backend/sql/agent_resource_scope.sql`（幂等，可重复运行）；新库由
> `deploy/docker/mysql/init/01-schema.sql` 直接带上 `workspace_id` 列。现有资源自动保持平台共享。

**3. 配置**：编辑 `backend/forge-admin/src/main/resources/application-dev.yml`，填入 MySQL 与 Redis 连接信息。

**4. 后端**：

```bash
cd backend
chcp 65001 -- for windows
mvn -Pdev clean package -DskipTests -T 1C
java -Dserver.port=8080 -Dfile.encoding=UTF-8 -jar forge-admin/target/forge-admin.jar
```

**5. 前端**：

```bash
cd frontend
chcp 65001 -- for windows
pnpm install --registry=https://registry.npmmirror.com
pnpm run dev
# 访问 http://localhost:8100（/dev-api 代理至 8080）
```

**6. 登录**：`admin / admin123`

## 上线前必改清单

面向公网部署时，以下默认值必须逐项更换（均为出厂演示值）：

1. **JWT 密钥**：`application.yml` 的 `jwt-secret-key`（默认为 26 个字母表，可被伪造令牌）；
2. **RSA 密钥对**：`application.yml` 中前后端加解密密钥（与前端 `VITE_APP_RSA_*` 成对轮换，需同窗口发布）；
3. **数据库 / Redis / 监控凭据**：`application-prod.yml` 中的默认密码与 snail-job token；
4. **Actuator 凭据**：端点已收窄为 `health,info,metrics,loggers,threaddump,httpexchanges`，`show-details` 改为认证后可见；仍需更换 Basic 认证凭据（出厂值在根 `pom.xml` 的 `monitor.username/password`，compose 部署可用 `.env` 的 `MONITOR_*` 覆盖）；
5. **数据库初始化脚本**：只执行新增增量脚本，不要重跑全量（重跑 `agent_mcp.sql` 会重建 `agent_approval` 并带回 NOT NULL 约束；应用启动时 `WorkflowSchemaGuard` 会自检工作流相关表结构并输出 WARN 指引）；
6. **免登录入口**：`/agent/trigger/webhook/**`（secret 鉴权）与 `/workflow-api/**`（API 密钥鉴权 + 限流）在生产默认放行，确认反向代理与防火墙策略与其预期一致；
7. **客服域密钥与开关**：启用客服 / 语音时，`agent.customer-service.visitor-token-secret` 与 `agent.callcenter.callback-secret`（≥16 字符）必须覆盖默认值；`agent.callcenter.mock-enabled` 在 prod 下必须为 `false`、`provider` 不得为 `AICC_MOCK`（生产就绪校验器会拒绝启动）；录音存储切换 `recording-store: oss` 并确认 `sys_oss_config` 可达；
8. **客服与语音免登录入口**：`/chat-api/**`（访客 HMAC 令牌）、`/chat-channel/**`（渠道验签）、`/chat-voice/**`（AICC 验签 + 时间窗 + nonce）各自带鉴权防线，确认反向代理不绕过这些头信息；
9. **记忆与图谱配置**：`memory.vector.model` 生产必须指向 `agent_model` 里的真实嵌入模型（出厂 `local-hash` 是特征哈希伪向量，不具备语义准确性）；`memory.governance.enabled` 按需开启（默认关，归档/配额/TTL 关闭时主链路行为不变）；`kg.*` 与 `agent.customer-service.kg-expand-enabled` 按需开启（默认关不影响既有功能）；
10. **知识库评测与清洗**：重建门禁 `rag.knowledge.gate.required` 默认关闭，开启后重建索引/差异重建前会按评测指标判决（BLOCKED 拒绝）；清洗规则 `clean_rules` 默认只开页眉页脚与空白归一，NFKC/PII 默认关（NFKC 会把全角标点一并半角化，中文语料慎开）。

## 测试与验证

```bash
# 后端全部单元测试（默认构建跳过测试，需同时显式覆盖 maven.test.skip 与 skipTests）
cd backend
# Agent 平台：检索链路与引用门禁 25（跨库/相关性/历史装配 + 引用校验硬门禁 + 资料块）+ 表达式引擎 11 + 图校验器 21 + 资源域守卫 8 + 记忆预算与工作记忆 8 = 73 用例
mvn -Pdev test "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-agent -am
# 知识库/RAG：整模块 145 用例 —— 解析与清洗 44（解析守卫/HTML/PDF/乱码/清洗器/规则）、分块与父子偏移 23、
# 阈值与检索回执 16、向量编解码与集合分片 11、MCP 检索端点协议 15、配置指纹 7、证据判定与指标 15、门禁判决 9、可见性解析 5
mvn -Pdev test "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-rag -am
# 智能客服域：customerservice 22 类 = 88 用例（含 A1~A5、P0~P3 增强与图谱维护/检索扩展）
mvn -Pdev test "-Dtest=CsModulePropertiesTest,CsTicketStatusTest,CsChannelGuardTest,CsKnowledgeAnswerServiceTest,WechatMpChannelAdapterTest,CsSecretCipherTest,CsLocalePolicyTest,SeatAssistServiceTest,SeatAssistVoiceTest,SeatAssistActionsTest,CsIntentServiceTest,CsAssistMetricServiceTest,CsHandoffKeywordsTest,CsSensitiveWordServiceTest,CsSemanticCacheServiceTest,CsSlotFillingServiceTest,CsKnowledgeGapServiceTest,CsCostServiceTest,CsAgentRouteServiceTest,CsKnowledgeGraphServiceTest,CsKgQueryExpanderTest,CsKgExpandMetricServiceTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" \
  -pl forge-modules/forge-customer-service -am
# 契约层：事件常量 / 验签 / PII 脱敏 = 13 用例
mvn -Pdev test "-Dtest=CsContractConstantsTest,CsChannelSignatureTest,CsPiiMaskerTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" \
  -pl forge-modules/forge-callcenter-common -am
# 呼叫中心：网关验签 / 状态机 / 事件指纹 / 录音存储 / 业务绑定 = 19 用例
mvn -Pdev test "-Dtest=AiccGatewayTest,CallStateMachineTest,CallFingerprintsTest,LocalFsRecordingStorageTest,CallBusinessBinderTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-callcenter -am
# 知识图谱模块：门面 9 + MySQL 存图 11 = 20 用例（含扩展词表与维护能力）
mvn -Pdev test "-Dtest=KgServiceTest,MysqlKgStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-kg -am
# 长期记忆：服务实现 19 + 向量服务 8 + 摘要范围 3 + 图注入 5 + 治理 9 = 44 用例
mvn -Pdev test "-Dtest=AgentMemoryServiceImplTest,MemoryVectorServiceTest,MemoryScopeTest,MemoryGraphInjectionTest,MemoryGovernanceServiceTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-memory -am

# 前端单元测试 + 全量类型检查（build 已内置 typecheck，类型不过则构建终止）
cd frontend
pnpm typecheck
pnpm test:unit
pnpm build
```

当前覆盖：后端 **402 用例**（Agent 平台 73 + 知识库/RAG 145 + 智能客服域 88 + 契约层 13 + 呼叫中心 19 + 知识图谱 20 + 长期记忆 44；验签与事件指纹用 Python 端生成的跨语言固定向量锁定）。测试类须标 `@Tag("dev")`（surefire 按 profile 过滤，缺注解的测试会被静默跳过——EXIT=0 不等于跑过，以 surefire 报告为准）。

## 路线图

- [x] 时间查询等内置工具集（get_current_time，经工具调用获取，不写入系统提示词）
- [x] 智能体评测与回归体系增强（评测集、LLM 裁判、黄金基线、发布门禁）
- [x] 调用链路可观测性（全链路 Run / Trace、阶段耗时、首 Token 延迟、运行链路下钻）
- [x] 工作流执行内核（并行分支 + 确定性 JOIN、失败策略与重试、dryRun、逐节点事件流、触发器幂等、对外 API 端点、对话触发路由、审批过期清扫）
- [x] 工作流引擎测试补齐（表达式引擎 / 图校验器 / 资源域守卫单测，40 用例）与聊天页工作流节点时间线
- [x] 免登录入口安全加固（限流前置到鉴权前、错误消息模糊化、请求体大小上限、超时取消后台任务、SSRF 防护）
- [x] 多租户在线服务加固（治理控制台空间校验、审批竞态条件更新、虚拟线程 Pinning 消除、调度池扩容、健康检查探依赖）
- [x] 运行快照截断、僵尸运行实例定时兜底、Cron 调度异步化
- [x] 资源域双模式（工具 / 数据源 / 模型的平台共享与空间私有，存量零行为变更）
- [x] 智能客服域（访客免登录 / 多渠道 / 七态工单 ACD / 流式 SSE / CSAT / 质检 / FAQ 飞轮 / PII 与留存合规）
- [x] 语音呼叫中心（AICC Mock 契约基线 → 零依赖契约公共层 → 回调适配层与 8 态状态机 → 通话与工单业务打通、录音合规链路）
- [x] 坐席辅助 A1~A5（双触发推荐、意图打标、白名单动作、采纳率看板与零采纳回流）
- [x] 客服 P0~P3 增强（关键词转人工 / 工单动作 / SLA / 敏感词 / 交接摘要；语义缓存 / 槽位 / 知识缺口 / badcase；成本 / 配额 / 附件 / 死信；意图路由 / 逐字 SSE / OpenAI 兼容端点）
- [x] 长期记忆体系六层化（L0 预算分配 / L1 工作记忆 / L2 滚动摘要 / L3 语义检索 / L4 归档 / L5 治理，MySQL 向量列方案）
- [x] 知识图谱模块 forge-kg（通用 scope、MySQL 存图 + Neo4j SPI、客服维护端点与页面、图谱辅助检索与效果指标、冲突词表）
- [x] 全量 TypeScript 类型检查接入构建（`build`/`build:dev`/`build:prod` 均前置 `vue-tsc --noEmit`；115 个存量错误已清零，含客服页 `.data?.data` 多取一层的运行时 bug）
- [x] 知识库检索内核增强 R1-A（检索回执与降级归因、阈值按执行口径判定、向量维度按集合分片、跨库 RRF 融合、精排专用池与硬超时）
- [x] 知识库摄取与索引硬化 R2（解析超时与恶意文件拦截、清洗层、分块 token 化与结构感知、索引代次与重建不断检、向量补偿同步、配置指纹与差异重建 + 解析产物复用）
- [x] 知识库评测闭环 R3（证据标注 span 判定、五指标 + Wilson 置信区间、双跑对比与重建门禁、零命中 → 用例 → 质量问题闭环）
- [x] 问答可信度 R4（结构化资料块注入、引用校验硬门禁、库级零召回兜底/严格拒答、答案层五维 LLM 裁判接 agent_eval）
- [x] 知识库治理 R5-1/R5-2（文档级 ACL 检索前下推、上传审核与发布流转、生效过期窗口、回收站软删与超期清理）
- [ ] 真实 AICC 接入（V0-real：替换 CsVoiceGateway 的 verify / parse / fetchRecording，业务层零改动）
- [ ] 语音出域登记与合规录音视图（`RECORDING_EGRESS`，`audio-egress-enabled=true` 场景）
- [ ] 生产知识图谱 Neo4j 后端实现（SPI 已就绪，注册 Bean 即切换）
- [ ] 知识库 R5-3~R5-6 按需（图谱辅助检索 / URL·S3·Confluence 外部源 / OCR 与多模态 / MCP filters）
- [ ] 向量库可插拔（Milvus 之外支持 pgvector / Qdrant）
- [ ] Agent 平台初始化 SQL 的 PostgreSQL 方言支持
- [ ] MCP 服务器补空间归属（当前仍为平台级资源）
- [ ] `AgentWorkflowServiceImpl` 剩余执行体进一步拆分（待下次功能迭代顺势进行）

## FAQ

**不部署 Milvus 能用吗？**
能，可以使用 **MySQL 9.7（社区版）** 的VECTOR类型支持向量存储。不过，需要注意的是：尽管 **MySQL 9.7（社区版）** 原生支持 VECTOR 类型，可以存向量；但是 InnoDB 本身没有 HNSW/IVF 向量索引，大规模 ANN 检索很慢。记忆 **向量规模不大（几千条以内）** 时，**MySQL 9.7（社区版）够用**；如果 **向量规模上万**、要高频相似度检索时，则 **不推荐直接用 MySQL 9.7（社区版）做向量检索层**，还是要上 Milvus / Qdrant /pgvector 更合适。因此，在无 Milvus 部署的环境下，知识库的向量化与检索能力受限，但其余功能（对话、编排、市场、治理等）正常。

**如何控制模型调用成本？**
Agent 级 Token 预算（发送前预检 + 轮级拦截）、平台级按日用量统计导出，两者配合使用。

**支持的数据库？**
MySQL 8 与 PostgreSQL（框架基座两者均支持；Agent 平台业务 SQL 目前为 MySQL 方言）。

## 协议与致谢

本项目基于 MIT 协议开源，衍生与致谢关系详见 [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES.md)，所使用的第三方组件及其版权声明亦汇总于该文件。
