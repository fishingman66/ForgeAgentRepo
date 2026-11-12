# forge-memory · 六层长期记忆

跨会话记忆沉淀、语义检索、图谱关联、注入预算与治理。15 个主类、1 个 Controller（`AgentMemoryController`）、**44 个单元测试**（5 类）。

方案与决策记录见 [`docs/03-长期记忆与知识图谱.md`](../../../docs/03-长期记忆与知识图谱.md)。全部能力落 MySQL（向量列）+ Redis，**零新基建**；默认关闭时主链路行为与未升级完全一致。

依赖方向：`forge-memory → forge-kg`（图谱）、`forge-rag`（复用 `EmbeddingFactory` / `VectorCodec`）、`forge-agent-core`（实体与模型工厂）、`forge-callcenter-common`（PII 打码契约）。L4 归档只落 forge-memory 内的纯 SQL，**不反向依赖 forge-agent**。

## 结构

```
com.forgeagent.memory
├── config/           # MemoryVectorProperties / MemoryGraphProperties / MemoryGovernanceProperties
├── controller/       # AgentMemoryController（/agent/memory）
├── domain/           # AgentMemory + bo/vo
├── mapper/           # AgentMemoryMapper / MemoryGovernanceMapper
└── service/
    ├── IAgentMemoryService
    └── impl/
        ├── AgentMemoryServiceImpl   # 抽取、写入、召回评分、事实去重、注入装配
        ├── MemoryScope              # 统一 agent + user 读写范围（防数据串号）
        ├── MemoryVectorService      # L3 语义向量（float32 小端 BLOB + 应用层余弦）
        ├── MemoryGraphInjection     # 图谱 1 跳邻接 + 冲突标注的「关联背景」渲染
        └── MemoryGovernanceService  # L4 归档 / L5 遗忘 / 配额 / TTL
```

## 六层能力

| 层 | 实现 | 要点 |
| --- | --- | --- |
| **L0 预算分配** | `ContextBudgetAllocator`（在 `forge-agent`） | 系统提示词 / 记忆 / 检索块 / 工作记忆 / 历史按优先级瓜分上下文 Token，历史设保底，系统提示词永远完整 |
| **L1 工作记忆** | `RunWorkingMemory` + `WorkingMemoryGuard`（在 `forge-agent`） | Redis `agent:run:wm:{runId}:`，`AgentTool` 装饰器注册层生效、各执行路径零改动；`RecallWorkingMemoryTool` 取回 |
| **L2 滚动摘要** | `SessionSummaryService`（在 `forge-agent`） | `agent_session.summary_text` + `summary_upto_msg_id` 水位 CAS 推进，最近消息保持原文 |
| **L3 语义检索** | `MemoryVectorService` | `agent_memory.embedding/embedding_dim/embedding_model`，float32 小端 BLOB + 应用层余弦，扫描上限 5000；融合 `0.6 × 语义相似度 + 0.4 × 现状评分`（`semantic-weight` 可配），事实键 `factKey` 去重；写入侧异步向量化 + 每轮补偿扫描双保险 |
| **L4 归档** | `MemoryGovernanceService` | `agent_message_archive` 同构归档表，会话最后消息超 `archive-retention-days`(180) 天且未置顶的消息分批转移（INSERT...SELECT + 按归档表 id 对账删主表），保留原 id 可回溯 |
| **L5 治理** | `MemoryGovernanceService` | 用户级遗忘（`confirm=FORGET`，级联 记忆含向量 → 图谱 `deleteScope` → 归档表，三层零残留）、容量配额（`agent_info.memory_quota` + 全局默认，写前淘汰低分 + 定时盘点，淘汰进归档不物理删）、TTL（`expire_at` 注入与召回双过滤 + 隐式 ttl-days 任务兜底） |

**注入路径**：召回（评分 + 语义融合）→ 预算裁剪 → 稳定事实条目（factKey 排序，KVCache 友好）→ 尾部「关联背景」（图谱 1 跳邻接 + 说法相反标注）。`GET /agent/memory/preview` 可预览与真实注入一致的记忆块，排查「召回了什么、被裁了什么」不用靠猜。

## 端点

```
GET    /agent/memory/list                        记忆列表（分页）
POST   /agent/memory                            手工新增
DELETE /agent/memory/{ids}                      删除
DELETE /agent/memory/agent/{agentId}            按 Agent 清理
GET    /agent/memory/preview                    注入预览（与真实注入一致）
GET    /agent/memory/search                     语义检索
GET    /agent/memory/graph/nodes  /graph/edges  图谱节点 / 边盘点
POST   /agent/memory/graph/rebuild              存量补建
DELETE /agent/memory/user/{userId}/forget?confirm=FORGET   用户级遗忘
POST   /agent/memory/governance/run             手动触发治理任务
```

前端管理页「智能体搭建 → 长期记忆」：记忆列表（分页 / 语义检索）、记忆图谱（实体与关系边、存量补建）、治理与遗忘（注入预览 / 遗忘 / 手动治理）。

## 配置

| 前缀 | 默认值 | 说明 |
| --- | --- | --- |
| `memory.vector.enabled` | `false` | L3 语义检索开关（dev 打开） |
| `memory.vector.backend` | `mysql` | **只认 `mysql`**；配 pgvector/milvus 会降级 + 告警一次（同值去重），`none` 为显式关闭不告警 |
| `memory.vector.model` | `local-hash` | **生产必须指向 `agent_model` 里的真实嵌入模型**；`local-hash` 是特征哈希伪向量，不具备语义准确性 |
| `memory.vector.top-k` / `min-score` / `scan-limit` / `pending-batch` / `semantic-weight` | 20 / 0.2 / 5000 / 20 / 0.6 | 检索与融合参数 |
| `memory.governance.enabled` | `false` | 治理总开关（默认关，关闭时主链路行为不变） |
| `memory.governance.scan-ms` | `600000` | **必须 > 0**：`@Scheduled(fixedDelayString)` 配 0 会抛异常导致应用起不来，关闭请用 `enabled` |
| `memory.governance.archive-retention-days` / `archive-batch` / `archive-max-batches` | 180 / 500 / 20 | L4 归档 |
| `memory.governance.ttl-days` / `default-quota` / `quota-evict-batch` | 0 / 0 / 50 | TTL 与配额（0 = 不限 / 关闭） |
| `memory.graph.inject-chars` / `neighbor-limit` / `rebuild-batch` / `rebuild-limit` | 220 / 5 / 10 / 200 | 注入字符预算（从记忆块总预算里扣）与存量补建批量 |

图谱本体配置在 `kg.*`（总开关 / 后端 / 扫描上限 / 冲突词表），见 [forge-kg/README](../forge-kg/README.md)。配置拆分原则：`kg.*` 管图谱本体，`memory.graph.*` 只管记忆侧自用参数。

## 相关 SQL

`agent_p2_memory.sql`（记忆主表）、`agent_p3_m1_session.sql`（会话摘要水位）、`agent_p3_m2_memory_vector.sql`（向量三列）、`agent_p3_m4_memory_governance.sql`（归档两表 + `expire_at` + `memory_quota` + 指标表）、`forge_kg_core.sql`（图谱两表）。全部幂等，已同步至 `deploy/docker/mysql/init/01-schema.sql`。

## 测试

**44 用例 / 5 类**，全部 `@Tag("dev")`：

```bash
mvn -Pdev test "-Dtest=AgentMemoryServiceImplTest,MemoryVectorServiceTest,MemoryScopeTest,MemoryGraphInjectionTest,MemoryGovernanceServiceTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" \
  -pl forge-modules/forge-memory -am
```

分组：服务实现 19 / 向量服务 8 / 摘要范围 3 / 图注入 5 / 治理 9。验收以 surefire 报告的 `tests/failures/errors` 为准，不认 EXIT=0。

## 已知边界与坑

- Postgres / pgvector 全局后置（用户明确），当前只有 MySQL 向量列方案；扫描上限 5000，规模上万应换专用向量库。
- `MemoryScope` 统一 agent + user 读写范围，曾因缺失导致跨用户数据串号；候选池与混合评分、`scoreOf` hit 节流是一并修掉的盘点缺陷。
- 抽取提示词禁敏感信息，三处 `CsPiiMasker` 打码（`forge-rag` 的 `KbPiiMasker` 正则与之逐字一致，改动需双向同步）。
- 纯 Mockito 无 Spring 的单测里 MyBatis-Plus lambda 列名解析会报 `can not find lambda cache`，需 `TableInfoHelper.initTableInfo(...)` 预热。
- BLOB 大字段必须 `@TableField(select=false)`，需要时显式 `.select(...)` 取；MP `updateById` 忽略 null 字段，要把列置 null 必须 `LambdaUpdateWrapper.set(col, null)`。
- 图谱 PII 打码留在业务域（`forge-kg` 不依赖 `CsPiiMasker`），三元组组装前由本模块完成打码。
