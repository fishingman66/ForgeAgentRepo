# forge-rag · 知识库与 RAG 模块

> 状态：**R1 检索内核 / R2 摄取与索引 / R3 评测闭环 / R4 问答可信 / R5 治理与规模化全部交付**（2026-11-12）。OCR 明确不做，改为视觉模型图片描述。方案与决策记录见 [`docs/05-知识库与RAG增强方案.md`](../../../docs/05-知识库与RAG增强方案.md)。

知识库文档摄取、清洗、分块、向量化、混合检索、重排序、评测门禁、治理、图谱辅助、外部源、图片描述与 MCP 端点。125 个主类、**6 个 Controller + 1 个 MCP 端点**、**196 个单元测试**（32 类）。

依赖方向：`agent-core ← forge-rag ← forge-agent`，另有 `forge-rag → forge-kg`（图谱第三路，单向）；`forge-memory → forge-rag`（复用嵌入工厂与向量编解码）。`forge-rag` 不依赖 `forge-agent` 的业务实现。

## 架构与数据流

```
摄取链: 上传 → 解析守卫(魔数/zip炸弹/扫描件/超时熔断)
        → 解析(pdf/docx/xlsx/html/txt/md/csv/json) + [[page=N]] 页面标记
        → [可选抽图: PDF Form XObject 递归 / docx 段落锚点 → [[img:n]]]
        → 清洗(页眉页脚/页码 → NFKC → 正则 → PII → 空白归一,代码块内跳过,PII 例外)
        → [可选图片描述: 按标记替换成【图片描述 pN】]
        → 结构感知分块(章节路径 / 表格保表头 / 代码原子块 / 中文句读边界)
        → [可选父子分块: 先切父块再切子块]
        → 嵌入(OpenAI 兼容 / local-hash 兜底,分批+超时+退避重试)
        → MySQL 子块落库(携带 generation) + 向量(milvus gRPC / mysql 向量列)
        → [换代: 新代写成功 → 切 active_generation → 清旧代]

检索链: 可见性解析(治理谓词 + ACL 角色求交 → hidden 集,检索前下推)
        → 三路召回(向量 + FULLTEXT ngram + 图谱第三路)
        → RRF 融合(k=60,跨库按 chunkId 破平)
        → 重排序精排(标准 Reranker API / LLM 打分,1500ms 硬超时回退)
        → 阈值过滤(按执行阶段选 RERANK / COSINE / NONE)
        → 缓存(60s,key 带可见性签名 + 参数覆盖签名 + 图路开关)
        → 命中统计 / 检索回执 SearchOutcome
        → 子块回填父块全文 + 页码定位

降级设计: 向量库挂 → 关键词兜底;Milvus 集合被删/未加载 → 重建或重载后重试一次(lite 自动加载,load 失败只记 debug);
          重排失败 → RRF 回退;向量写失败 → 标 FAILED 由补偿任务重试,不阻断解析;
          嵌入网络失败 → 1s/2s 退避重试 3 次;
          图片描述失败 → 写【图片 pN】(未生成描述),任何失败不阻断摄取;
          hidden 集过大 → 改允许列表,再大则跳过向量路并记 DOC_SCOPE_FILTER_OVERFLOW
```

## 目录说明

```
src/main/java/com/forgeagent/rag
├── config/           # KnowledgeProperties(rag.knowledge.*)、异步线程池
├── controller/       # AgentKnowledgeController / DocController / EvalController /
│                     # QualityController / ExtSourceController / KbVlmConfigController /
│                     # KnowledgeMcpEndpoint(/mcp)
├── domain/           # 知识库 / 文档 / 分块 / 评测 / ACL / VLM 配置实体 + bo/vo
├── mapper/           # Mapper（全文检索 SQL 含可见性下推）
├── service/
│   ├── document/     # 解析器 / 解析守卫 / 清洗器 / 分块器 / 抽图 / 图片描述 / 指纹
│   ├── embedding/    # EmbeddingFactory + OpenAI 兼容 + local-hash
│   ├── vector/       # VectorStore + Milvus/Mysql 实现 + VectorCodec + VectorCollections
│   ├── rerank/       # ApiRerankService / LlmRerankService / RerankService
│   ├── retrieve/     # SearchOutcome / DegradedReason / ScoreThresholdPolicy /
│   │                 # ScoreNormalizer / ScoreType / ParentOffsets / ParentRedactor
│   ├── eval/         # EvalEvidence / SpanHitJudge / EvalMetricsCalculator /
│   │                 # GateJudgeCore / CorpusFingerprints / KbDocumentGenerationEvent
│   ├── governance/   # DocVisibilityResolver / DocTrashCleanupService
│   ├── graph/        # GraphQueryTokenizer / KbGraphRetriever / KbKgExtractService
│   ├── ext/          # ExtSourceConnector SPI + Url / S3 / Confluence + SsrfGuard
│   ├── support/      # KnowledgeRetrievalOverrides / KbSecretCipher /
│   │                 # KbVlmConfigResolver / QueryHashes
│   └── impl/         # 知识库 / 文档 / 摄取管线 / 评测 / 质量 / 外部源 /
│                     # 向量补偿 / VLM 配置 服务实现
└── src/test/java     # 32 类 196 用例,全部 @Tag("dev")
```

## 检索内核（R1）

- **阈值只能比可比分数**：`rag-min-score` 曾直接比 `effectiveScore`（未开精排时 = RRF 融合分，上限约 0.033），生产默认 0.05 导致对话 RAG **恒零命中**。判定统一走 `ChatRetrievalSupport.passRelevance`（精排分 → 余弦 → 仅关键词命中不参与）；`ScoreThresholdPolicy` 按执行阶段选目标，NONE 时 `inactive=true`。归一化分只用于展示与跨库比较，**不改写绝对阈值口径**。
- **跨库合并是跨库 RRF**（`1/(k + 库内名次)`，chunkId 破平），不是「每库除以 max 归一化」——后者让每库 top1 恒为 1.0，排序退化成库遍历顺序。
- **向量维度按集合分片**（`VectorCollections`）：主集合沿用配置名（存量零迁移），其他维度落 `{collectionName}_{dim}`；维度由模型实际输出决定（`EmbeddingFactory#resolveDimension` + 惰性定维 + `warmUp()`），`vector-dim` 只是 local-hash 与探测失败的兜底。
- **降级回执**：`search()` 语义不变，`searchOutcome()` 才返回 `SearchOutcome`（hits + degraded + thresholdAppliedOn + 各路召回数 + 耗时）。`agent_kb_query_log` 与零命中日志并存（前者答「走成什么样」，后者答「什么没找到」）。精排有 1500ms 硬超时 + 队列 0 的专用池，排不进立刻回退粗排。

## 摄取与索引（R2）

| 能力 | 实现 | 要点 |
| --- | --- | --- |
| 解析守卫 | `DocumentParseGuard` | 魔数校验、zip 炸弹 / 恶意文件拦截、扫描件识别与明确报错、解析超时熔断 |
| 清洗层 | `DocumentCleaner` + `CleanRules` | 库级 `clean_rules` JSON（NULL=默认：页眉页脚/空白归一开，**NFKC/PII 关**）。管线顺序钉死：页眉页脚/页码 → NFKC → 正则 → PII → 空白归一，代码块内跳过（PII 例外）。`KbPiiMasker` 与 callcenter-common `CsPiiMasker` 正则逐字一致，改动需双向同步 |
| 分块 | `TextChunker` | `chunk_unit`(char/token) × `split_strategy`(structure/fixed) 双参数，输出 `ChunkPiece(text, headingPath)`；默认 char，复用 `chunk_size` 一列按单位解释，非法值保存时拒绝 |
| 父子分块 | `ParentOffsets` / `ParentRedactor` | 子块嵌入召回、父块回填全文；父块会把已禁用子块原文一起送出的问题已在 R1 修掉 |
| 索引代次 | `active_generation` / `generation` | 重建三步「新代写成功 → 切指针 → 清旧代」，检索只认 `==`（不是 `>=`）；切代瞬间读竞态靠整次重跑并绕过缓存 |
| 向量补偿 | `KbVectorSyncService` | `vector_status`(PENDING/SYNCED/FAILED/SKIPPED) + `vector_retry_count`，30s / batch 200 / 上限 5 次；写失败只标 FAILED + 文档 `notice`，不再让文档整单失败 |
| 配置指纹 | `KnowledgeFingerprints` | parse / chunk 双指纹；变化或换嵌入模型 → `config_stale=1`，默认不自动重建，`PUT /{id}/reindex-stale` 差异重建；`parsed_text`（≤400 万字符、`select=false`）存清洗后文本，**文档级 parse 指纹相同则跳过解析+清洗**（改 chunkSize 只重切，改 cleanRules 才重解析） |

> 分块编辑顺序：算向量 → 落库 → 写向量库，写失败抛异常回滚；反过来会留下「文本旧、向量新」的永久不一致。

## 评测闭环（R3）

`service/eval/` 为纯静态组件：证据标注（evidences JSON，span 覆盖阈值 0.5，跨块区间并集；文档级只比 docId）+ 五指标（HitRate / Recall / Precision / MRR / NDCG@K + Wilson95% 仅展示）+ `GateJudgeCore` 双跑门禁（ε=max(0.02,1/N)，三态 PASSED / BLOCKED / LOG_ONLY，语料指纹不等 → NOT_COMPARABLE）+ 质量问题 3 态闭环（`(kb,source,query_hash)` 去重，RESOLVED 复发自动 reopen）+ case-from-hit 调试页生成用例。自动打标走 `KbDocumentGenerationEvent`（Processor → KbEvalService → IAgentKnowledgeService 是循环依赖，禁止直接注入）；门禁接 Controller（`rag.knowledge.gate.required` 默认关）。逐用例明细落 `eval_run.case_results_json` 供交集重算。

**基线定版**：HitRate@5 **93.33%** / MRR **0.8667** / NDCG@5 **0.8841**（`docs/baseline/kb_baseline.py --no-write`，30 用例）。可复现三前提：同分排序破平、夹具串行摄取、每轮 `OPTIMIZE TABLE agent_kb_chunk`（InnoDB 全文索引对已删行异步清理会改 IDF，这是 MRR 每轮漂 0.01~0.02 的真因）。

## 问答可信度（R4）

由 `forge-agent` 侧消费：`ChatRetrievalSupport.buildReferenceBlock(...)` 生成结构化资料块（JSON 引用列表 + 合法编号清单进 prompt，尖括号转 `\u003c` 防伪分隔符）、`AnswerCitationValidator` 硬门禁（只认 Markdown 文本节点 `[n]`，代码/链接/图片剔除，越界拒发；接线 `finishRound` 开头全链路覆盖，流式走既有 error 通道，mock runner 跳过）、库级零召回兜底（`fallback_answer_enabled`，严格模式一律拒答）、agent_eval 五维裁判（correctness / faithfulness / completeness / citation_correctness / refusal_correctness，均分 ≥4 通过）。

## 文档级 ACL 与内容治理（R5-1 / R5-2）

`DocVisibilityResolver` 把「治理谓词（`trashed` / `publish_status` / 生效过期窗口）+ ACL 角色求交」算成 hidden 集，**检索前下推**：MySQL 全文 `document_id NOT IN` + 同谓词 JOIN、Milvus `not in`（>500 改允许列表，>2000 跳过向量路并记 `DOC_SCOPE_FILTER_OVERFLOW`）、MySQL 向量列 `notIn`；`loadChunksAndDocs` 后纵深防御再裁一次。

- **检索缓存键必须带可见性签名**，否则跨角色串号；未登录 = 无角色 = RESTRICTED 默认拒绝。
- 治理动作只改 DB 行（审核 / 发布态 / 时间窗 / 回收站软删），**O(1) 不碰向量库**；物理清理集中在 `DocTrashCleanupService`（retention 30 天，删除分块 / 向量 / 原文件 / 文档行）。
- 端点：`GET /agent/knowledge/doc/list?trashed=true`、`PUT /restore/{ids}`、`PUT /governance/{id}`、`PUT /review/{id}`；上传按 `review_required` 落 `PENDING_REVIEW` 或 `PUBLISHED`。

## 图谱第三路（R5-3）

- 摄取侧 `KbKgExtractService` 监听 `KbDocumentGenerationEvent` 异步抽取三元组写 `forge-kg`，并写 `agent_kb_kg_entity` 分块映射（`kg.extract-enabled` 默认关，`kg.extract-model` 填 `agent_model.model_name`，1 chunk 1 次模型调用）。
- 检索侧 `GraphQueryTokenizer`（CJK 整段 + 2~4 字滑窗，字母数字 run 整段，去单字）→ `KbGraphRetriever`（种子实体 → ≤2 跳 `kgService.neighbors` → `1/(1+hops)` 打分 → 同块取 max → hidden 过滤 → 回落 chunkId）。
- **图谱只贡献 RRF 名次分**，不参与余弦/精排分数；命中分块仍走 `enabled + generation` 复核。开关 `graph-route-enabled` 默认关。

## 外部源（R5-4）

`ExtSourceConnector` SPI：`type()` + `fetch(configJson) → List<ExtDocument(externalId,title,content)>`。

| 连接器 | 状态 |
| --- | --- |
| `UrlExtSourceConnector` | **真抓取**：`SsrfGuard` 仅 http/https、DNS 解析后拒 loopback/私网/链路本地/组播、**禁用重定向**、5MB / 20s 上限、HTML 转纯文本 |
| `S3ExtSourceConnector` | 骨架，明确抛「尚未接入（SPI 已就绪）」，不静默入空文档 |
| `ConfluenceExtSourceConnector` | 骨架，明确抛「尚未接入」 |

同步按条目级 `(source_id, external_id)` 增量去重，逐条 `importText` 入库（MD5 去重 + 按审核态定发布态），失败更新条目状态。端点 `POST /agent/knowledge/ext-source`、`GET /list`、`POST /{id}/sync`。

## 库级检索参数与 MCP（R5-6）

- `retrieval_overrides`（`KnowledgeRetrievalOverrides`）：`topK` / `searchMode` / `vectorWeight` / `keywordWeight` / `scoreThreshold` / `rerank`，留空跟随全局。优先级 **调用方显式 > 库级 > 全局**；两路权重只放大/缩小各自 RRF 贡献；`signature()` 参与缓存键。
- MCP 端点 `/mcp`（`mcp-enabled` 默认关，`mcp-token` 建议内网配）：3 个工具

| 工具 | 说明 |
| --- | --- |
| `knowledge_search` | 检索片段，支持 `filters.documentIds` 作为允许列表下推到 `DocVisibilityResolver.resolve(kbId, onlyDocIds)` |
| `knowledge_list` | 列出启用的知识库，取 knowledgeId |
| `knowledge_chunks` | 按文档取分块明细 |

协议能力：`Mcp-Session-Id`（30min TTL）、版本协商（2025-06-18 / 2025-03-26）、JSON-RPC 批处理、`structuredContent`、规范错误码（-32700/-32600/-32601/-32602）、GET/DELETE 405。限制：无 SSE 响应、无服务器发起能力。**`/mcp`、`/mcp/**` 必须留在 `security.excludes`**（存量缺陷：曾一直被登录拦截返回 401）；未握手时 MCP 不下发 `structuredContent`，调用方要能从 `content[0].text` 解析。

## 图片描述与视觉模型配置（R5-5 + 追加）

**不做 OCR**，改为「抽图 → 视觉模型生成描述 → 写进正文」：

- `DocumentParser#parseDocument` 返回 `ParsedDocument(text, images)`；PDF 抽 XObject（**必须递归 Form XObject**，pymupdf/Office 导出常放表单里；<200px 过滤、MD5 去重、单图 2000 万像素上限、单篇 20 张），docx 按段落锚点抽 `XWPFPicture`。正文插入 `[[img:n]]`。
- `KbImageDescriber` 按标记替换：关 = 剥离；开 + 无模型 = 剥离 + 告警一次；成功 = `【图片描述 p3】...`；失败 = `【图片 p3】（未生成描述）`。**任何失败不阻断解析**。同图描述 Redis 复用 30 天，图片落 `fileDir/images/{docId}/`。
- 全局 `vlm-enabled` + 库级 `vlm_enabled` **双开关默认关**；`vlmSignature` 参与 `parse_fingerprint`。实体开关字段是 `Integer`，判定用 `Integer.valueOf(1).equals(...)`（用 `Boolean.TRUE.equals` 会恒 false 并静默只剥离不描述）。

**按工作空间配置图片识别模型**（`agent_kb_vlm_config`，`workspace_id` 唯一，`0` = 平台默认）：

| 来源 | 说明 |
| --- | --- |
| `PLATFORM` | 复用 `agent_model` 里的平台模型凭证 |
| `CUSTOM` | 第三方 OpenAI 兼容：`baseUrl` + `apiKey` + `modelName` |

API Key 经 `KbSecretCipher`（AES-GCM，`ENC:` 前缀，密钥由 `rag.knowledge.secret-key` SHA-256 派生）加密落库；**任何接口只回显掩码**（`****abcd`），编辑留空沿用旧密文。`KbVlmConfigResolver` 优先级：**工作空间行 > 平台默认行 > 全局 `vlm-model`**，`effectiveSource` 回给界面。端点 `GET /agent/knowledge/vlm-config?workspaceId=`、`PUT`、`POST /test`（连通性测试，失败返 `{ok:false,message}`，不报 500）。

> **上线必改**：`rag.knowledge.secret-key` 默认值是弱口令，生产必须替换；换密钥后既有密文无法解密，需重新保存配置。

## 向量存储：Milvus 与 MySQL 本地版

`VectorStore` 是唯一抽象（另有带 `DocFilter` 的可见性下推重载），检索链全部注入接口，换实现不动业务代码。

| 值 | 实现 | 适用 | 说明 |
| --- | --- | --- | --- |
| `milvus`（默认） | `MilvusVectorStore` | 本地 milvus-lite + 生产 standalone | `milvus-sdk-java 3.0.12` 走 **gRPC**（`MilvusClientV2`），AUTOINDEX + COSINE；集合启动时懒创建，SDK 自带 shaded gRPC/Netty |
| `mysql` | `MysqlVectorStore` | 本地开发 | 向量存 `agent_kb_chunk.embedding`（float32 小端 BLOB），检索在应用层算余弦 |

```yaml
# 本地开发（milvus-lite 3.x，19530 仅 gRPC）
rag:
  knowledge:
    vector-store: milvus
    milvus-host: 127.0.0.1
    milvus-port: 19530
    milvus-consistency-level: STRONG   # 写入后立即可见
# 生产（Milvus 3.0 standalone）：只改 host/port/token/database/secure，
# 业务代码与 SDK 版本完全一致，见 application-prod.yml 与 deploy/docker/docker-compose.yml
```

`vector-store: mysql` 仅用于「不装 Milvus」的临时场景。**切换后端后必须执行一次** `backend/sql/manual_vector_backend_resync.sql`（非幂等）：`vector_status` 是「当前后端已同步」的单列状态，存量 `SYNCED` 分块不会被补偿任务捞起，不回填则语义召回静默为空。也可以对每个知识库调用 `PUT /agent/knowledge/{id}/reindex` 全量重建。

边界：MySQL 版检索是 O(n) 全扫，单次最多 5000 分块，**上万分块就该上 Milvus**；维度不符的分块跳过（换嵌入模型后老向量自动失效）；`embedding` 标 `@TableField(select=false)` 需显式 select；升级脚本 `backend/sql/agent_kb_local_vector.sql`（幂等）。`local-hash` 是特征哈希伪向量，**只能验证链路通不通，不能评估召回质量**。

## 关键配置（rag.knowledge.*）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `vector-store` / `milvus-host/port/token` | `milvus` / 127.0.0.1:19530 / 空 | gRPC 连接；SDK 3.0.12，lite 与 standalone 通用 |
| `milvus-database` / `milvus-secure` / `milvus-consistency-level` | 空 / false / BOUNDED（dev 配 STRONG） | 库隔离 / TLS / 一致性 |
| `collection-name` / `vector-dim` | `agent_kb_chunk` / 384 | 集合名与兜底维度（真实维度以模型输出为准） |
| `file-dir` / `max-file-size-mb` | `./data/knowledge` / 20 | 文档存储 |
| `rag-top-k` / `rag-max-hits` / `rag-min-score` | 3 / 5 / 0.05 | 对话检索参数（阈值只比可比分数） |
| `cross-kb-rrf-k` | 60 | RRF 融合常数 |
| `query-rewrite-enabled` / `rewrite-timeout-ms` | true / 2000 | 多轮查询改写 |
| `rag-context-budget` / `rag-neighbor-expand` | 2000 / true | 注入预算 / 邻块扩展 |
| `rag-cache-enabled` / `rag-cache-ttl-seconds` | true / 60 | 检索缓存 |
| `rerank-timeout-ms` / `rerank-candidate-limit` | 1500 / 50 | 精排硬超时与候选上限 |
| `mcp-enabled` / `mcp-token` | `false` / 空 | MCP 端点开关与令牌（空=不校验，仅建议内网） |
| `gate-required` / `citation-validate-enabled` | `false` / `true` | 重建门禁 / 引用硬门禁 |
| `trash-cleanup-enabled` / `trash-retention-days` | `true` / 30 | 回收站物理清理 |
| `graph-route-enabled` / `graph-max-hops` / `graph-entity-limit` / `graph-chunk-limit` | `false` / 2 / 10 / 20 | 图谱第三路 |
| `kg-extract-enabled` / `kg-extract-model` / `kg-extract-max-chunks` | `false` / 空 / 60 | 异步抽实体 |
| `vlm-enabled` / `vlm-model` / `vlm-min-pixels` | `false` / 空 / 200 | 图片描述（库级另需 `vlm_enabled`） |
| `secret-key` | `forge-kb-secret-change-me` | **VLM API Key 加密主密钥，生产必须替换** |
| `sync-compensation-enabled` / `sync-compensation-batch-size` / `sync-compensation-max-retry` / `sync-pending-timeout-minutes` | true / 200 / 5 / 5 | 向量补偿 |

## 父子分块 / 重排序双路由 / 评测集

- **父子分块**：编辑页开启并设父块大小（默认 2000 字符）后，先切父块（不嵌入、不召回）再切子块（嵌入+召回），命中子块回填 `parentContent`；开关只影响新摄取文档，存量需重建索引一次。
- **重排序**：模型名含 `rerank` → 标准 `POST {base}/v1/rerank`（兼容硅基流动/百炼/Jina/Cohere）；其他模型 → LLM 逐条打分（候选截断 20 条），30s 超时失败回退 RRF 粗排。
- **评测集**：`POST /agent/kb/eval/case`、`POST /agent/kb/eval/run?knowledgeId=&topK=`、`GET /agent/kb/eval/runs`，另有 `/fingerprint`、`/compare`、`/gate` 支撑双跑门禁。

## 相关 SQL

全部幂等增量脚本，位于 `backend/sql/`：

| 批次 | 脚本 |
| --- | --- |
| 基础 | `agent_knowledge_doc.sql`、`agent_kb_p2.sql`、`agent_kb_parent_chunk.sql`、`agent_kb_page_meta.sql`、`agent_kb_eval.sql`、`agent_kb_rerank_threshold.sql`、`agent_kb_optimize.sql`、`agent_hybrid_search.sql`、`agent_kb_local_vector.sql` |
| R1~R2 | `agent_kb_r1_retrieval.sql`、`agent_kb_r2_ingest.sql`、`agent_kb_r2_chunking.sql`、`agent_kb_r2_clean.sql`、`agent_kb_r5_fingerprint.sql` |
| R3~R4 | `agent_kb_r3_eval.sql`、`agent_kb_r3_quality.sql`、`agent_kb_r4_trust.sql` |
| R5 | `agent_kb_r5_acl.sql`、`agent_kb_r5_scale.sql`、`agent_kb_r5_vlm.sql`、`agent_kb_vlm_config.sql` |

Docker 路径 `deploy/docker/mysql/init/01-schema.sql` 已同步全部表与列。

## 测试与验证

**196 用例 / 32 类**，全部 `@Tag("dev")`；其中 `MilvusVectorStoreContractTest` 13 条为 gRPC 报文契约，`MilvusGrpcIntegrationTest` 2 条需 `-Dmilvus.it=true` 连真机：

```bash
mvn -Pdev test "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-rag -am
```

分组：解析与清洗 34（`DocumentCleaner` 12 / `DocumentParseGuard` 10 / `CleanRules` 7 / `GarbledTextDetector` 5）、HTML/PDF/抽图/描述 18、分块与指纹 17、父子偏移 12、阈值与检索回执 16、向量编解码与集合分片 11、MCP 端点协议 15、证据判定与指标 15、门禁判决 9、可见性解析 5、图路 8、SSRF 4、参数覆盖 4、摄取管线 2、Key 加密 5、模型解析 6。

端到端脚本（含编译、打包、jar 冒烟、造数、断言与清理）：`temp/r5_verify.py` 28/28（ACL 与治理）、`temp/r5b_verify.py` 18/18（图谱 / 外部源 / MCP）、`temp/r5c_verify.py` 15/15（图片描述）、`temp/r5d_verify.py` 16/16（工作空间视觉模型配置）。检索基线 `docs/baseline/kb_baseline.py --no-write` 多轮不降。

**纪律**：根 pom `maven.test.skip=true`，必须同时覆盖 `-Dmaven.test.skip=false -DskipTests=false`；验收以 `target/surefire-reports/TEST-*.xml` 的 `tests/failures/errors` 为准，不认 EXIT=0。e2e 断言用「目标 documentId 是否在命中集」，别用 `hits==0`（local-hash 伪向量会误召其他可见文档）；MySQL NATURAL LANGUAGE MODE 只有一行时所有词算高频被忽略，造数至少 2 个分块。

## 已知边界与扩展点

- MySQL 向量后端是 O(n) 全扫（上限 5000），生产上万分块须用 Milvus；**Milvus 3.0 gRPC 是本地 lite 与生产 standalone 的唯一通道**（SDK 3.0.12，切环境只改连接配置）；pgvector / Qdrant 未实现。
- S3 / Confluence 外部源为 SPI 骨架，实现 `fetch` 即启用；URL 源已真抓取。
- 图谱第三路默认关，抽取需配真实模型；`kg.extract-model` 留空不会抽。
- 图片描述不做 OCR，扫描件 PDF 仍由解析守卫明确报错。
- `TextChunker` 拼重叠尾段必须带 `\n`（曾丢换行导致父子偏移一律定位失败）；定位还要能剥「【章节路径】」前缀并忽略空白匹配。
- MyBatis `@Select` 的 `<script>` 必须让字面量**直接以 `<script>` 开头**（文本块前置换行会退化成裸 SQL，关键词路静默降级为仅向量路）。
- 新增 Vo 必须挂 `@AutoMapper(target = Entity.class)`，否则 `selectVoPage` 抛 ConvertException 而接口只回「未知异常」。
