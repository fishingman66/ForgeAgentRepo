# 智造 ForgeAgent · 企业级智能体平台

把一个 AI 助手从想法推进到业务落地，中间隔着模型接入、提示词调试、知识库准备、工具对接、权限隔离、上线运营一整条链路。智造 ForgeAgent 把这条链路装进一个平台：以**工作空间**为资源与数据隔离边界，团队在一个系统里完成智能体的开发、运行、分发与治理。

> 本文是**总览与导航**：先让你跑起来，再按域给出能力全景，细节一律指向 `backend/README.md` 与 12 份模块 README，避免同一件事在多处各写一遍。方案与决策记录见 [`docs/README.md`](docs/README.md)。

| 速览 | 值 |
| --- | --- |
| 模块 | `backend/forge-modules/` 下 12 个，依赖单向无环 |
| 后端单测 | **442 用例**（7 个模块有测试类，全部 `@Tag("dev")`） |
| 前端 | 15 单测 + `vue-tsc --noEmit` 零错误，已接入构建门禁 |
| 检索基线 | HitRate@5 **93.33%** / MRR **0.8667** / NDCG@5 **0.8841** |
| 端到端 | 4 套逐期脚本（28 / 18 / 15 / 16）全通过 |
| 协议 | MIT |

## 目录

[1. 它解决什么问题](#1-它解决什么问题) · [2. 快速开始](#2-快速开始) · [3. 架构与模块](#3-架构与模块) · [4. 核心能力](#4-核心能力) · [5. 上线前必改清单](#5-上线前必改清单) · [6. 测试与验证](#6-测试与验证) · [7. 路线图](#7-路线图) · [8. FAQ](#8-faq) · [协议与致谢](#协议与致谢)

---

## 1. 它解决什么问题

- **模型散、入口乱**：统一管理模型供应商与模型配置，业务按需选用，不改代码即可切换
- **知识不进对话**：文档上传、解析、分块、向量化全流程内置，对话自动携带知识库检索结果
- **能力难复用**：编排好的智能体发布到市场，其他团队安装即用，避免重复建设
- **运行不透明**：Token 用量、审计日志、用户反馈全链路可查，成本与质量都有据可依

---

## 2. 快速开始

### 方式一：Docker Compose（推荐，新库表结构自动带齐）

```bash
cd deploy/docker
cp .env.example .env
# 按需修改 .env 中的密码等配置
docker compose up -d
# 访问 http://localhost:80
```

默认管理员：`admin / admin123`。

> **Docker 新库表结构与种子已补齐**（2026-11-12）：`01-schema.sql` 现含全部 **128 张表**，与 `forge-vue` 开发库的表/列完全对齐（含客服 `agent_cs_*`、语音 `agent_cc_*`、图谱 `kg_node`/`kg_edge`、记忆归档与评测门禁）。全部 `CREATE TABLE IF NOT EXISTS`、增量列直接写进建表定义，可重复执行；中文注释乱码已修复。`02-seed.sql` 含 **261 条菜单**（含智能客服 12 个页面入口与知识库「检索日志」，否则表有了但导航里没入口），`03-demo.sql` 的演示账号/空间/Agent 可重复执行。`backend/sql/` 下 78 个幂等增量脚本仍用于**存量库升级**，新库（含 Docker）无需逐个执行。
>
> **演示环境每日重置**：`.env` 里 `DEMO_ENABLED=true` 会开启后端写保护并在登录页展示「演示账号一键登录」；配合 `bash demo/reset-demo.sh`（建议 crontab `0 4 * * *`）清空 demo 账号与演示空间产生的运行时数据、恢复演示种子、清空 Redis。清理范围与保留项见脚本头部注释。

### 方式二：本地开发

**1. 环境**：JDK 21、Maven 3.9+、**Node ≥ 22.22.0 与 pnpm ≥ 12.0.0**（见 `frontend/package.json` 的 `engines`）；MySQL 8、Redis 6 必装，Milvus 2.4 可选。

> 不装 Milvus 也能跑通知识库全链路：`rag.knowledge.vector-store: mysql` 用 MySQL 向量列做本地检索后端，详见 §4.2 与 [forge-rag README](backend/forge-modules/forge-rag/README.md)。

**2. 初始化数据库**（创建 `forge-vue` 与 `forge-job`，均 utf8mb4）：

```sql
CREATE DATABASE `forge-vue` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE `forge-job` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

```bash
mysql --default-character-set=utf8mb4 -u用户名 -p forge-vue < backend/script/sql/mysql/forge_vue.sql
mysql --default-character-set=utf8mb4 -u用户名 -p forge-vue < backend/script/sql/mysql/forge_workflow.sql
mysql --default-character-set=utf8mb4 -u用户名 -p forge-job < backend/script/sql/mysql/forge_job.sql
# Agent 平台增量脚本（78 个，全部幂等）
for f in backend/sql/agent_*.sql; do mysql --default-character-set=utf8mb4 -u用户名 -p forge-vue < "$f"; done
# 图谱两表是 forge_ 前缀，不在上面通配范围内，必须单独导入
mysql --default-character-set=utf8mb4 -u用户名 -p forge-vue < backend/sql/forge_kg_core.sql
```

> - 导入务必带 `--default-character-set=utf8mb4`，否则中文双重编码。
> - **新库**可通配全量导入；**升级存量库只执行新增脚本，不要重跑全量**——字母序不等于依赖序，重跑 `agent_mcp.sql` 会 drop 并重建 `agent_approval`，把 `agent_step5.sql` 已放开的 `session_id` 非空约束带回来，导致含人工审批节点的工作流执行失败。应用启动时 `WorkflowSchemaGuard` 会自检并输出 WARN 与脚本路径指引。
> - 资源域双模式升级单独执行 `backend/sql/agent_resource_scope.sql`（幂等），现有资源自动保持平台共享。
> - Windows 终端执行前先 `chcp 65001`，避免控制台中文乱码。

**3. 配置**：编辑 `backend/forge-admin/src/main/resources/application-dev.yml`，填入 MySQL 与 Redis 连接信息。

**4. 后端**：

```bash
cd backend
mvn -Pdev clean package -DskipTests -T 1C
java -Dserver.port=8080 -Dfile.encoding=UTF-8 -jar forge-admin/target/forge-admin.jar
```

**5. 前端**：

```bash
cd frontend
pnpm install --registry=https://registry.npmmirror.com
pnpm run dev
# 访问 http://localhost:8100（/dev-api 代理至 8080）
```

**6. 登录**：`admin / admin123`

---

## 3. 架构与模块

### 3.1 技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3.5 + TypeScript 6 + Element Plus 2.14 + Vite 8 + Pinia 4 + vue-router 5 + UnoCSS + ECharts；pnpm 12 |
| 后端 | Spring Boot 4.1 + JDK 21 + Sa-Token + MyBatis-Plus + MapStruct Plus |
| 运行内核 | AgentScope 2.0 |
| 存储 | MySQL 8+（utf8mb4）+ Redis 6（Redisson）+ Milvus 2.4（可选）。无 Milvus 时向量以 `BLOB`（float32 小端）存于分块表/记忆表，由应用层算余弦，扫描上限 5000 |

### 3.2 模块地图

单体多模块，12 个模块依赖单向无环；每份 README 有完整端点、配置、表结构与测试说明。按「被依赖 → 依赖方」分层：

| 层 | 模块 | 依赖 |
| --- | --- | --- |
| L0 零业务依赖 | `forge-callcenter-common`（仅 JDK）、`forge-kg`（仅 common）、`forge-agent-core`（仅 common） | — |
| L1 | `forge-rag` | agent-core、kg |
| L2 | `forge-memory` | agent-core、kg、rag、callcenter-common |
| L3 | `forge-agent` | agent-core、rag、memory |
| L4 | `forge-customer-service` | agent、callcenter-common、kg |
| L5 | `forge-callcenter` | customer-service、callcenter-common |
| 旁支 | `forge-system`、`forge-workflow`、`forge-gen`、`forge-job` | 仅框架基座，不参与业务域依赖 |

即 `callcenter → customer-service → agent → rag / memory → agent-core / kg`，反向一律禁止；`memory → kg`、`customer-service → kg`、`rag → kg` 三处共用图谱且互不依赖。

| 模块 | 职责 | 文档 |
| --- | --- | --- |
| `forge-agent-core` | 共享内核：实体 / `ResourceAccessGuard` / 模型工厂 | [README](backend/forge-modules/forge-agent-core/README.md) |
| `forge-agent` | Agent 平台业务与运行内核（27 Controller） | [README](backend/forge-modules/forge-agent/README.md) |
| `forge-rag` | 知识库与 RAG（R1~R5 全量，181 用例） | [README](backend/forge-modules/forge-rag/README.md) |
| `forge-memory` | 六层长期记忆（L0~L5） | [README](backend/forge-modules/forge-memory/README.md) |
| `forge-kg` | 知识图谱（MySQL 存图 / Neo4j SPI） | [README](backend/forge-modules/forge-kg/README.md) |
| `forge-customer-service` | 智能客服域（C0~C4 / A1~A5 / P0~P3） | [README](backend/forge-modules/forge-customer-service/README.md) |
| `forge-callcenter` | 语音呼叫中心（AICC Mock 链路） | [README](backend/forge-modules/forge-callcenter/README.md) |
| `forge-callcenter-common` | 零业务依赖契约层（事件信封 / 验签 / PII） | [README](backend/forge-modules/forge-callcenter-common/README.md) |
| `forge-workflow` | OA 审批工作流（WarmFlow + LiteFlow） | [README](backend/forge-modules/forge-workflow/README.md) |
| `forge-system` | 系统管理基座（用户 / 角色 / 菜单 / OSS） | [README](backend/forge-modules/forge-system/README.md) |
| `forge-gen` | 代码生成（FreeMarker 模板） | [README](backend/forge-modules/forge-gen/README.md) |
| `forge-job` | SnailJob 定时任务示例 | [README](backend/forge-modules/forge-job/README.md) |

后端整体说明（逐模块测试命令、配置表、SQL 清单、运行保障约定）见 [backend/README.md](backend/README.md)；前端见 [frontend/README.md](frontend/README.md)。

### 3.3 资源域模型（平台共享 / 空间私有）

工具、数据源、模型三类资源带 `workspace_id`：`NULL` = 平台共享（全空间可用、仅超管可建改），非空 = 空间私有（仅归属空间可见）。共享资源含连接凭据与 API 密钥。列表、详情、Agent 绑定、对话、工作流、问数、对外 API 与触发器全部经 `ResourceAccessGuard` 把可见性**下推到 SQL**（不是查出来再过滤）；免登录链路以工作流归属 Agent 的空间作为 `callerWorkspaceId`。引用空间私有资源的 Agent 禁止发布到市场。

`agent.resource-sharing.enabled=false` 可一键收紧为全私有解释（存量 NULL 资源需运维先迁移归属）。详见 [forge-agent-core README](backend/forge-modules/forge-agent-core/README.md)。

### 3.4 设计决策

这些取舍记录在此，供评估与二次开发参考：

- **单体多模块，而非微服务**：交付速度优先，模块边界按业务内聚划分、依赖单向；待某模块出现独立扩缩容需求时再拆。
- **运行内核基于 AgentScope 2.0**：ReAct 循环、流式事件、钩子由内核承担，自研集中在资产管理、隔离与治理层。
- **系统提示词保持 KVCache 友好**：动态内容（如当前时间）不写进系统提示词，改由工具调用获取，避免破坏模型供应商前缀缓存。
- **混合检索优于单一向量检索**：关键词命中与向量召回经 RRF 融合，配合父子分块与可配置重排，低资源文档场景更稳。
- **代码执行默认隔离**：沙箱一次性容器运行，内存 / CPU / 网络 / 超时全部硬约束。
- **界面「内容为主、操作随行」**：消息气泡只承载内容，操作工具栏常驻气泡外侧。
- **两套工作流各司其职**：「Agent 编排画布」是面向一次 LLM 任务的自研 JSON DAG；`forge-workflow` 是面向 OA 审批的流程引擎。两者零依赖、互不调用，不要混用。

---

## 4. 核心能力

每个域的完整端点、配置项、表结构、SQL 与测试明细都在对应模块 README；这里只给全景与关键取舍。

### 4.1 智能体开发与运行

可视化 DAG 编排（校验 / 试运行 / 版本快照与回滚）、ReAct 内核 + 结构化意图路由与四种协作模式（顺序 / 真并行 / 末棒复核 / 主控规划）、结构化输出与多模态图片输入。对话侧 SSE 流式输出（断线续传复用全局 `RunEventBuffer` 游标重放）、Token 预算双重拦截、多轮查询改写、回答引用溯源、重新生成与反馈。运行侧有全链路 Run/Trace（阶段耗时 / 首 Token 延迟）与跨实例取消令牌；治理侧有版本快照、Prompt 模板三态绑定、效果评测与黄金基线、发布门禁与豁免审批；分发侧有智能体市场（安装 / 评分 / 审核）与团队接力。集成侧有 MCP 服务器管理与工具审批、HTTP 扩展节点、Docker 沙箱执行、Cron / Webhook 触发器（投递幂等防重）。

→ [forge-agent README](backend/forge-modules/forge-agent/README.md)

### 4.2 知识库与 RAG（R1~R5 全部交付）

| 批次 | 交付 |
| --- | --- |
| R1 检索内核 | 检索回执与降级归因；阈值按执行阶段只比可比分数（精排分 / 余弦，不比 RRF 分）；向量维度按集合分片；跨库 RRF 融合；精排 1500ms 硬超时 + 专用池 |
| R2 摄取与索引 | 解析守卫（超时熔断 / zip 炸弹 / 扫描件报错）、清洗层（页眉页脚 / NFKC / 正则 / PII / 空白归一）、char\|token × 结构感知\|定长分块与父子偏移、混合检索 + 重排双路由、索引代次化（重建不断检）、向量补偿同步、双指纹 + `reindex-stale` 差异重建 + 解析产物复用 |
| R3 评测闭环 | 证据标注 span 判定、五指标 + Wilson95%、双跑对比与重建门禁（三态）、零命中 → 用例 → 质量问题 3 态闭环 |
| R4 问答可信度 | 结构化资料块、引用校验硬门禁、库级零召回兜底 / 严格拒答、答案层五维 LLM 裁判接 `agent_eval` |
| R5-1/2 治理 | 文档级 ACL + 内容治理：hidden 集**检索前下推**（全文 SQL / 向量 filter，绝不召回后过滤）、缓存按可见性隔离；审核 / 发布态 / 生效过期窗口 / 回收站软删与 30 天清理 |
| R5-3/4/6 规模化 | 图谱第三路（异步抽实体、≤2 跳、只走 RRF）；URL 外部源 + SSRF 防护 + S3·Confluence SPI 骨架；MCP `filters.documentIds` / `knowledge_chunks`；库级检索参数覆盖 |
| R5-5 与视觉模型 | PDF/docx 抽图 → 描述插入对应页/段落（双开关默认关、≤20 张、同图 MD5 复用、失败不阻断，**不做 OCR**）；按工作空间配置平台或第三方 OpenAI 兼容模型，Key AES-GCM 加密 / 掩码回显 / 编辑留空沿用 / 连通性测试 |

向量后端 `milvus`（生产）/ `mysql`（本地，`BLOB` + 应用层余弦，上限 5000）一个配置切换，上万分块应切 Milvus。完整口径见 [forge-rag README](backend/forge-modules/forge-rag/README.md) 与 [`docs/05-知识库与RAG增强方案.md`](docs/05-知识库与RAG增强方案.md)。

### 4.3 智能客服与语音呼叫中心

三个模块共享零业务依赖契约层，依赖单向 `callcenter → customer-service → callcenter-common`；默认全关（`agent.customer-service.enabled` / `agent.callcenter.enabled`），开启不影响既有功能。

- **文本客服**：免登录 `/chat-api/**`（HMAC 访客令牌 + Origin 白名单 + 限流）、渠道回调 `/chat-channel/**`（验签 + 时间窗 + nonce）、七态工单与 ACD、逐字 SSE、CSAT / 质检 / FAQ 飞轮、PII 与留存擦除、渠道密钥加密。P0~P3 补齐关键词转人工 / SLA / 敏感词 / 交接摘要、语义缓存 / 槽位 / 知识缺口 / badcase、成本 / 配额 / 附件 / 死信、意图路由 / 坐席 SSE / OpenAI 兼容端点。
- **语音呼叫**：`POST /chat-voice/{provider}/{code}` 免登录回调（`AiccGateway` 收口供应商差异，验签 + 时间窗 + nonce + 事件幂等，固定返 `{message, retcode}` + HTTP 200）、8 态状态机单调推进（乱序与迟到 CDR 不回退）、通话↔会话↔工单打通与挂机结案自动 CSAT、录音 `local-fs` / `oss` 一个配置切换且只存对象键 + 短期签名 URL + 审计。
- **坐席辅助 A1~A5**：文本 / 语音转写双触发（异步旁路、语音五道判定）、规则意图打标与检索加权、白名单动作 `REPLY` / `LINK` / `RESOLVE`（坐席确认后执行）、采纳率按**推荐条数**、连续零采纳自动生成待审 FAQ。
- **图谱领域能力**：`forge-kg` 提供关系维护端点与空间隔离、`CsKgQueryExpander` 检索词扩展、日指标对比卡（观测口径非 A/B）。

端点 / 状态机 / 配置 / 表结构见 [forge-customer-service](backend/forge-modules/forge-customer-service/README.md) / [forge-callcenter](backend/forge-modules/forge-callcenter/README.md) / [forge-callcenter-common](backend/forge-modules/forge-callcenter-common/README.md) README。语音回调渠道为双表同码并存（`agent_cs_channel` + `agent_cc_channel_config`），缺一不可。

### 4.4 长期记忆与知识图谱

六层记忆全部落 MySQL + Redis、零新基建、默认关闭时主链路零行为变更：**L0** 预算分配 → **L1** 工作记忆（`WorkingMemoryGuard` 装饰器，注册层生效）→ **L2** 水位 CAS 滚动摘要 → **L3** 语义检索（向量列 + 应用层余弦，`0.6×语义 + 0.4×评分` 融合、事实键去重）→ **L4** 归档（`agent_message_archive`，180 天，保留原 id）→ **L5** 治理（用户级遗忘 `confirm=FORGET` 级联 记忆含向量 → 图谱 → 归档、配额、TTL 双过滤）。注入路径「召回 → 预算裁剪 → 稳定事实条目（KVCache 友好）→ 关联背景」，`GET /agent/memory/preview` 可预览与真实注入一致的记忆块。

图谱 `forge-kg` 零业务依赖：`KgScope` 三元组（`user_id=0` 为共享级）+ `KgStore` SPI / `KgService` 门面，MySQL 两表默认实现，换 Neo4j 只需注册一个 `backend()="neo4j"` 的 Bean。消费方三处：记忆注入、客服领域维护、RAG 图谱第三路。配置拆分 `kg.*`（本体）与 `memory.graph.*`（记忆侧自用参数）。详见 [forge-memory](backend/forge-modules/forge-memory/README.md) / [forge-kg](backend/forge-modules/forge-kg/README.md) README。

### 4.5 工作流、对外集成与运行保障

- **工作流**：12 类节点（并行分支 + 确定性 JOIN、人工审批 + 超时清扫）、失败策略三分支（终止 / 继续 / 跳转）与节点级重试超时、dryRun / 指定起跑 / 步数保险、模板深取 `{{a.b[0].c}}`、试运行异步化 + 逐节点事件流、触发器与对外 API 双通道（幂等防重）；Agent 可配触发模式（关闭 / 每轮自动执行 / 按意图路由）
- **对外 API**：`/workflow-api/**` 免登录 + 空间 API 密钥鉴权 + 每分钟限流 + 超时硬顶 + 幂等防重
- **平台治理**：工作空间成员与角色、API Key（SHA-256 存储）、审计日志、Token 用量统计与按日导出、运营看板
- **免登录防线**：`/agent/trigger/webhook/**`、`/workflow-api/**`、`/chat-api/**`、`/chat-channel/**`、`/chat-voice/**` 各有鉴权；三层限流（IP 预限流 / 端点限流 / 鉴权失败计数）、请求体 256KB 上限、错误消息模糊化
- **运行保障**：http 节点 SSRF 防护、审批竞态条件更新、Cron 调度异步化、僵尸 running 定时兜底、轨迹快照截断、健康检查探 DB/Redis 依赖

---

## 5. 上线前必改清单

面向公网部署时，以下出厂演示值必须逐项更换：

| # | 项 | 说明 |
| --- | --- | --- |
| 1 | JWT 密钥 | `application.yml` 的 `jwt-secret-key`（默认 26 个字母表，可伪造令牌） |
| 2 | RSA 密钥对 | 前后端加解密密钥与前端 `VITE_APP_RSA_*` 成对轮换，需同窗口发布 |
| 3 | 数据库 / Redis / 调度凭据 | `application-prod.yml` 默认密码与 snail-job token |
| 4 | Actuator 凭据 | 端点已收窄为 `health,info,metrics,loggers,threaddump,httpexchanges`、`show-details: WHEN_AUTHORIZED`；仍需更换 Basic 认证（出厂值在根 `pom.xml` 的 `monitor.username/password`，compose 可用 `.env` 的 `MONITOR_*` 覆盖） |
| 5 | 数据库初始化 | **只执行新增增量脚本**，不要重跑全量（理由见 §2 快速开始的导入注意） |
| 6 | 免登录入口 | `/agent/trigger/webhook/**`（secret 鉴权）、`/workflow-api/**`（API 密钥 + 限流）生产默认放行，确认反向代理与防火墙策略符合预期 |
| 7 | 客服域密钥与开关 | `agent.customer-service.visitor-token-secret`、`agent.callcenter.callback-secret`（≥16 字符）必须覆盖；prod 下 `agent.callcenter.mock-enabled=false`、`agent.callcenter.provider` 不得为 `AICC_MOCK`（`CallcenterProductionReadinessValidator` 会拒绝启动）；录音切 `recording-store: oss` 并确认 `sys_oss_config` 可达 |
| 8 | 客服与语音免登录入口 | `/chat-api/**`（访客 HMAC）、`/chat-channel/**`（渠道验签）、`/chat-voice/**`（AICC 验签 + 时间窗 + nonce）确认反向代理不绕过这些头 |
| 9 | 记忆与图谱 | `memory.vector.model` 必须指向 `agent_model` 里的真实嵌入模型（出厂 `local-hash` 是伪向量，不具语义准确性）；`memory.governance.enabled`、`kg.*`、`kg-expand-enabled` 按需开启（默认关，关闭时主链路零行为变更） |
| 10 | 知识库 | 重建门禁 `rag.knowledge.gate.required` 默认关，开启后重建前按评测指标判决（BLOCKED 拒绝）；`clean_rules` 默认只开页眉页脚与空白归一（NFKC 会把全角标点半角化，中文语料慎开）；**`rag.knowledge.secret-key` 生产必须替换**（默认弱口令，换密钥后既有 VLM 密文需重新保存） |

---

## 6. 测试与验证

### 覆盖口径

数字一律按各模块 `src/test/java` 的 `@Test` 重数，不按历史累加口径。

| 模块 | 用例 | 模块 | 用例 |
| --- | --- | --- | --- |
| Agent 平台 | 73 | 知识库/RAG | 181 |
| 智能客服域 | 92 | 长期记忆 | 44 |
| 知识图谱 | 20 | 呼叫中心 | 19 |
| 契约层 | 13 | **后端合计** | **442** |

前端 15 单测（`sse-events` 10 + `governance` 5），`pnpm typecheck` 零错误，`build` / `build:dev` / `build:prod` 均前置 `vue-tsc --noEmit`。

### 常用命令

```bash
cd backend
# 单模块全量单测（换 -pl 即可切模块；根 pom 默认跳过测试，两个参数必须同时写）
mvn -Pdev test "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-rag -am
# 指定测试类（-Dtest= 场景必须带 failIfNoSpecifiedTests=false）
mvn -Pdev test "-Dtest=KgServiceTest,MysqlKgStoreTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" \
  -pl forge-modules/forge-kg -am

cd frontend && pnpm typecheck && pnpm test:unit && pnpm build
```

**逐模块完整命令（含各模块 `-Dtest=` 类清单）见 [backend/README.md「模块测试」](backend/README.md)**；各模块 README 另附分组明细。验收以 `target/surefire-reports/TEST-*.xml` 的 `tests/failures/errors` 为准——**EXIT=0 不等于跑过**（缺 `@Tag("dev")` 会被静默过滤，报告目录只有 `*.dumpstream` 就是假绿）。

### 端到端与基线

`temp/` 下逐期脚本各自包含编译、打包、jar 冒烟、造数、断言与清理：`r5_verify.py` 28/28（ACL 与治理）、`r5b_verify.py` 18/18（图谱 / 外部源 / MCP）、`r5c_verify.py` 15/15（图片描述）、`r5d_verify.py` 16/16（工作空间视觉模型配置）。

检索评测基线 `docs/baseline/kb_baseline.py --no-write` 定版 **HitRate@5 93.33% / MRR 0.8667 / NDCG@5 0.8841**，多轮回归不降。可复现三前提：同分排序破平、夹具串行摄取、每轮 `OPTIMIZE TABLE agent_kb_chunk`（详见 [`docs/README.md`](docs/README.md)）。

---

## 7. 路线图

### 已交付

平台基座（内置工具集 / 评测回归 / Run·Trace / 资源域双模式 / 多租户加固 / 免登录加固 / TypeScript 类型检查接入构建，115 个存量错误清零）、工作流（12 类节点引擎 / 对话触发 / 对外 API / 审批清扫）、智能客服（C0~C4 / A1~A5 / P0~P3 / AICC Mock 语音链路）、记忆与图谱（六层 L0~L5 / `forge-kg` 独立模块）、知识库（R1~R5 全量，含图片描述与视觉模型按工作空间配置）、验证体系（4 套 e2e + 检索基线定版）**均已交付**。逐批结论与决策偏差见 `docs/` 五篇方案文档的「批次历史」表。

### 待办

- [ ] 真实 AICC 接入（V0-real：替换 `CsVoiceGateway` 的 verify / parse / fetchRecording，业务层零改动）
- [ ] 语音出域登记与合规录音视图（`RECORDING_EGRESS`，`audio-egress-enabled=true` 场景）
- [ ] 生产知识图谱 Neo4j 后端实现（SPI 已就绪，注册 Bean 即切换）
- [ ] S3·Confluence 外部源真实接入（SPI 已就绪，实现 `fetch` 即启用）
- [ ] 向量库可插拔（Milvus 之外支持 pgvector / Qdrant）
- [ ] Agent 平台初始化 SQL 的 PostgreSQL 方言支持
- [ ] MCP 服务器补空间归属（当前仍为平台级资源）
- [ ] `AgentWorkflowServiceImpl` 剩余执行体进一步拆分

---

## 8. FAQ

**不部署 Milvus 能用吗？**

能，而且**知识库全链路可跑通**。把 `rag.knowledge.vector-store` 设为 `mysql` 后，向量以 float32 小端 `BLOB` 存在 `agent_kb_chunk.embedding`，由应用层计算余弦相似度（单次扫描上限 5000 分块），上传、解析、分块、检索、对话注入、评测都不需要 Milvus。注意这不是 MySQL 的原生 `VECTOR` 类型，InnoDB 也没有 HNSW/IVF 向量索引，属于 O(n) 全扫：几千分块约几十毫秒够用，**上万分块或高频 ANN 检索请切 Milvus / pgvector / Qdrant**。记忆侧的 L3 语义向量同理，见 [forge-rag README](backend/forge-modules/forge-rag/README.md) 与 [forge-memory README](backend/forge-modules/forge-memory/README.md)。

**如何控制模型调用成本？**

Agent 级 Token 预算（发送前预检 + 轮级拦截）、平台级按日用量统计导出，两者配合使用；客服域另有会话级成本口径与空间当日 token 配额（WARN / BLOCK）。

**支持的数据库？**

MySQL 8 与 PostgreSQL（框架基座两者均支持；Agent 平台业务 SQL 目前为 MySQL 方言，PG 方言支持在待办清单中）。

---

## 协议与致谢

本项目基于 MIT 协议开源，衍生与致谢关系详见 [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES.md)，所使用的第三方组件及其版权声明亦汇总于该文件。
