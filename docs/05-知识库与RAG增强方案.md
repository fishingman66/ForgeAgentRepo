# ForgeAgent 知识库与 RAG 系统（现状、决策与下一步）

> **定位**：当前实现的事实源 + 设计取舍依据 + 踩坑记录 + 下一步方向。方案期流水账已删，实现细节以代码为准。
> R1~R5 已全部交付（2026-11-11 ~ 11-12），对标项目 `kb-rag-project`（M1~M24）。
> 检索效果的可复现基线见 [baseline](baseline/)；Agent 平台能力见 [01](01-平台增强演进.md)。

---

## 0. 一页结论

1. **主链完整**：解析 → 清洗 → 分块 → 嵌入 → 双后端索引 → 双路召回 + 跨库 RRF → 精排 → 父子回填 → 引用校验 → 评测门禁 → 治理/图谱。不必重建。
2. **基线定版**（6 篇语料 / 30 用例 / 36 分块）：HitRate@5 **93.33%** / MRR **0.8667** / NDCG@5 **0.8841**。改检索相关代码必须复跑 `docs/baseline/kb_baseline.py` 对比，不得下降。
3. **相对 kb-rag 的优势**：结构感知分块（表格保表头、代码原子块、章节前缀，kb-rag 是定长硬切）、父子禁用子块精确挖除、MCP 协议套件（15 用例）。别为"对标"丢掉这些。
4. **下一步三个方向**（详 §6）：① 关键词路中文召回能力；② 摄取可信度（解析预览 + 扫描件）；③ 检索洞察与内容缺口分析。

---

## 1. 能力现状（按链路）

### 1.1 摄取

| 环节 | 实现 | 关键约束 |
| --- | --- | --- |
| 上传校验 | `DocumentParseGuard`：魔数比对、zip 条目 ≤2000 / 解压 ≤500MB / 单条目 ≤100MB / 防 zip-slip、`ZipSecureFile.setMinInflateRatio(0.01)`、POI 禁 DTD | 不合格文件不落盘 |
| 解析 | `DocumentParser#parseRich` 返回 `ParsedDocument(正文 + images)`；PDFBox 逐页抽 XObject（**递归 Form XObject**）、docx 按段落锚点抽图；html 走零依赖 `HtmlDocumentParser` | 60s 硬超时、20M 字符 / 2000 页上限、解析与嵌入分池（`knowledgeParseExecutor`） |
| 乱码/扫描件 | `GarbledTextDetector`（采样 4000 字符，可识别占比 <50% 判乱码跳过）；**扫描件标失败**（`status=2` + 原因含"需 OCR"），不标"可用但 0 块" | 绿标 0 块运营发现不了，红标可重试可删除 |
| 图片 | 抽出图片 → 正文写 `[[img:n]]` → `KbImageDescriber` 用 VLM 描述替换；过滤 <200px、MD5 去重、单图 2000 万像素、单篇 20 张；同图描述 Redis 复用 30 天 | **无 OCR、无图片向量**，纯扫描页只能靠描述兜底 |
| 清洗 | `DocumentCleaner`，顺序**钉死**：页眉页脚/页码 → NFKC → 正则 → PII → 空白归一 | 见 §2 取舍 |
| 分块 | `TextChunker`「计价器 + 策略」：`chunk_unit=char|token`（**默认 char**）、`split_strategy=structure|fixed`（**默认 structure**）；硬上限 4000 字符 | 非法配置**保存时拒绝**，不静默回退 |
| 父子 | 子块落 `parent_offset_start/end`，落库前校验 `parentText.substring(s,e).equals(childText)` | 偏移失效就不落，检索期整块放弃 |

### 1.2 索引

| 项 | 实现 |
| --- | --- |
| 向量后端 | `vector-store=milvus`（dev 直连 milvus-lite 3.x、prod 用 Milvus 3.0 standalone v3.0.6，**统一 gRPC + `milvus-sdk-java 3.0.12`**）/ `mysql`（无 Milvus 时，`agent_kb_chunk.embedding` BLOB + 应用层余弦）。集合按维度分片 `{name}_{dim}`，主集合沿用原名零迁移 |
| 维度 | `EmbeddingFactory#resolveDimension` 以模型真实输出为准（`warmUp()` 惰性自校准），`vector-dim` 只作 local-hash 兜底 |
| 索引代次 | 文档 `active_generation` / 分块 `generation`。「新代写成功 → 切指针 → 清旧代」，检索只认 `==`。**切代读竞态整次重跑并绕过缓存** |
| 一致性 | `vector_status`(PENDING/SYNCED/FAILED/SKIPPED) + `KbVectorSyncService` 补偿（30s / batch 200 / maxRetry 5 / PENDING 宽容 5min）；写顺序固定「算向量 → 落库 → 写向量」 |
| 配置指纹 | 双轨 `parse_fingerprint` / `chunk_fingerprint`（sha256，含 `PARSE_PIPELINE_VERSION`）；任一指纹或嵌入模型变化 → 文档 `config_stale=1`，默认**不自动重建**，`PUT /{id}/reindex-stale` 差异重建 |
| 产物复用 | `parsed_text`（≤400 万字符才落，`@TableField(select=false)`），改分块参数不再重解析 |

### 1.3 检索

| 项 | 实现 |
| --- | --- |
| 双路召回 | 向量（Milvus/MySQL）+ MySQL `FULLTEXT ... WITH PARSER ngram` |
| 分数契约 | `ScoreType`：`VECTOR_COSINE`/`RERANK`（跨 query 可比）、`RRF`（仅本次可比）、`BM25_RANK`（不可比，仅排序）。`ScoreNormalizer`：cosine → (cos+1)/2 clamp |
| 阈值 | `ScoreThresholdPolicy` 按运行阶段选 `RERANK`/`COSINE`/`NONE`；**阈值只比可比分数**（`ChatRetrievalSupport#passRelevance`：精排分 → 余弦 → 仅关键词命中不参与）。返回 `thresholdAppliedOn` |
| 跨库 | 跨库 RRF `1/(60 + 库内名次)` + chunkId 破平；单库短路不做 RRF；并发池 `core=4/max=16/queue=0`（队列 0 刻意：排队白烧调用方超时预算） |
| 精排 | 候选上限 50、1500ms 硬超时 + `cancel(true)`、失败四分类（超时/拒绝/异常/条数不符）回退 RRF；`rerank.mode=HYBRID` 时 `0.7*rerank + 0.3*minmax(bm25)`，**阈值仍作用于纯 rerank 分** |
| 改写 | 2000ms 超时、结果 Redis 缓存 10min（`sha256(query+history)`）、输出单行/去引号/≤200 字符，超长回退原文 |
| 可解释 | `DegradedReason` 8 种 + `SearchOutcome` 回执（hits/降级/阈值口径/各路召回数/耗时）；落 `agent_kb_query_log`（异步，失败只记日志） |
| 父子回填 | 库级 `hide_parent_with_disabled_child`（默认 `true` 整块丢弃，宁可丢上下文不漏禁用内容）；`false` 时按偏移挖除 + 占位符，**任一偏移失效整块返回** |
| 图谱第三路 | `GraphQueryTokenizer`（CJK 整段 + 2~4 滑窗）→ 实体匹配 top10 → ≤2 跳 → `1/(1+hops)` 打分、同块 max；**只贡献 RRF 名次**；命中仍按 enabled+generation 复核（图是滞后数据） |
| 库级覆盖 | `agent_knowledge.retrieval_overrides`（topK/模式/两路权重/阈值/精排），优先级 **调用方显式 > 库级 > 全局**；缓存键带可见性签名 + 覆盖签名 |

### 1.4 问答与评测

| 项 | 实现 |
| --- | --- |
| 资料块 | `buildReferenceBlock`：JSON 引用列表（citation/document/location/content）+ 合法编号清单写进 prompt + 尖括号转义（防伪 `<<<KB_REF_END>>>` 提前闭合）；零召回写明「本次检索未召回任何资料」并禁止 `[数字]` |
| 引用校验 | `AnswerCitationValidator` 硬门禁：只解析 Markdown 文本节点 `\[\d+\]`，越界即拒发；流式在 `done` 之前校验，失败发 `error` 不发 `done`；**不改写答案、不裁剪引用** |
| 拒答 | 库级 `fallback_answer_enabled`（默认 true 宽松 / false 严格）；多库全严格才走严格版 |
| 评测标注 | `agent_kb_eval_case.evidences` JSON `[{"documentId","span"}]`，span 覆盖阈值 0.5；legacy 用例标 `legacy=true` 分组隔离；文档换代自动打 `EVIDENCE_STALE`（Spring 事件解耦） |
| 命中判定 | `SpanHitJudge`：normalize=NFKC+去空白+去掩码+小写；`aggregateCoverage` 用**区间并集**（解决 span 横跨切分边界被低估）；最小匹配长度按语种（CJK≥2 字、拉丁≥4 字母） |
| 指标 | HitRate@K / Recall@K / Precision@K / MRR / NDCG@K + Wilson 95%（**仅展示，永不参与门禁**） |
| 门禁 | `GateJudgeCore`（Agent/KB 共用）：`corpus_fingerprint` 不等→NOT_COMPARABLE；`ε = max(2pp, 1/N)`；三态 PASSED/BLOCKED/LOG_ONLY；`rag.knowledge.gate.required` 默认关 |
| 答案层 | `agent_eval` 五维 Judge（correctness/faithfulness/completeness/citation_correctness/refusal_correctness），只在有期望答案/期望拒答的用例上跑 |
| 质量问题 | `agent_kb_quality_issue` 3 态 `NEW→IN_PROGRESS→RESOLVED`（+reopen），按 `(knowledge_id, source, query_hash)` 去重，RESOLVED 后同指纹再登记自动 reopen |

### 1.5 治理

| 项 | 实现 |
| --- | --- |
| 文档 ACL | `visibility=INHERIT|RESTRICTED` + `agent_kb_doc_acl(document_id, role_id)`；**检索前下推**（召回后过滤会击穿 topK 与 ACL） |
| 可见性 | `DocVisibilityResolver` 统一算 hidden 集：治理谓词（trashed=0 / PUBLISHED / 生效窗口）+ ACL 角色求交；**未登录默认拒绝**；`loadChunksAndDocs` 后再裁一次 |
| 下推实现 | MySQL `document_id NOT IN (...)` + 同谓词 JOIN；Milvus `not in`，>500 改允许列表，>2000 **跳过向量路记 `DOC_SCOPE_FILTER_OVERFLOW`**（宁可少一路，不做后置过滤） |
| 内容治理 | `publish_status`(DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED) + `effective_at`/`expires_at` + `trashed`（30 天 `DocTrashCleanupService` 物理清理）；**治理动作只改行 + 失效缓存，不碰向量库** |
| 审核 | 库级 `review_required`（只影响后续上传），`PUT /doc/review/{id}` 审核 |
| 外部源 | `service/ext/` SPI + `UrlExtSourceConnector`（`SsrfGuard`：仅 http/https、拒内网/回环/链路本地、**禁重定向**、5MB/20s）；S3/Confluence **骨架明确报错**，不静默入空文档 |
| VLM 配置 | `agent_kb_vlm_config`（workspace_id 唯一，0=平台默认），`source_type=PLATFORM/CUSTOM`；API Key AES-GCM 加密（`ENC:` 前缀），**只回显掩码、编辑留空沿用旧密文**；优先级 工作空间行 > 平台默认 > 全局 `vlm-model` |

---

## 2. 关键设计与取舍

| 决策 | 结论 | 依据 |
| --- | --- | --- |
| 阈值语义 | 只比可比分数，按运行阶段定目标 | 精排失败时按配置比精排分会误杀；配置表达不了"这次按哪种分过滤"。**任何"统一用 score 比阈值"的写法都会让恒零命中复活** |
| 跨库合并 | 跨库 RRF，不用归一化加权 | 不同库 embedding/精排模型不同，原始分尺度不可比；库内 max 归一化会让每库 top1 恒为 1.0 |
| 归一化边界 | 归一化分只用于展示与跨库比较 | 存量库阈值是按原始余弦配的，改口径会让所有已配阈值的行为突变 |
| 维度隔离 | 集合按维度分片，主集合沿用原名 | 强制单维度等于"不允许按库选模型"；分片让存量零迁移 |
| 切换嵌入模型 | 允许切换（原实现直接拒绝），切换后必须重建 | 原逻辑死锁：要切模型得先重建，重建用的还是旧模型 |
| 分块单位 | `char`/`token` 都提供，**默认都用 char** | 基线实测标题密集语料上两种口径结果完全一致（预算没成约束），没证据支撑改默认 |
| 分块策略 | 只实现 `structure`/`fixed` | `heading`/`page`/`separator` 无真实场景支撑，不做"配了没用"的旋钮；非法值拒绝而非静默回退 |
| 清洗 NFKC | **默认关** | NFKC 会把全角逗号/括号一并半角化，中文标点全变、证据判定漂移。需要时按库显式开 |
| PII 脱敏 | 默认关；正则与 `CsPiiMasker` 逐字一致（类独立） | rag 与 callcenter-common 无业务交集，不为一个工具类引横向依赖 |
| 零停机重建 | `generation` 代次，不做完整版本表 | 拿到「重建期旧数据可用 + 失败可退」，成本 1 个 int 列；版本历史价值在内容审计 |
| 向量失败语义 | 分块标 FAILED + 补偿，**不让文档失败** | 文本已可用（关键词能召回），整篇标失败用户直接看不到内容 |
| 同步状态粒度 | 单列 `vector_status`，不建 chunk_sync 表 | 同一时刻只启用一个向量后端，无"一 chunk 多后端"场景 |
| 评测标注 | 文档 + 精确 span + 0.5 覆盖阈值 | 「文件名 contains + 关键词 contains」既假降又假升，指标方向错了后续全错 |
| 门禁容差 | `ε = max(2pp, 1/N)`，Wilson CI 不参与 | 一个用例翻面 = 2pp 是运营能理解的噪声下限；两套噪声控制并存判决不可解释 |
| 与 `agent_eval` | 不合并表，只复用 `GateJudgeCore` | 样本结构、快照、指标都不同，硬合并两边都复杂 |
| 质量问题 | 轻量 3 态 | 参考项目四阶段 + 流水表价值在多人协作，单团队过度设计 |
| 图谱 | 只贡献 RRF 名次，禁止「图谱 + weighted」组合 | hop 折扣分是第三把尺子，加权融合的权重按两路 min-max 定义，没有可用权重 |
| 治理动作 | 只改行 + 失效缓存 | 不碰向量库，代价 O(1)，与语料规模无关 |
| 缓存键 | 必须带可见性签名 | 不同角色的缓存不能串号，一次串号等于越权泄露 |

---

## 3. 踩坑与回归要点

**会让检索静默失效的（改这块必看）**

1. `rag-min-score` 直接比 `score`（RRF 分上限 ≈0.033）会导致生产恒零命中。阈值只能走 `passRelevance`，只比可比分数。
2. MyBatis `@Select` 的 `<script>` **必须让字符串字面量直接以 `<script>` 开头**。文本块前置换行会退化成裸 SQL，关键词路静默降级为仅向量路（`AgentKbChunkMapper#searchByKeyword` 长期如此，因 RRF 下向量路已覆盖、基线没变而一直没被发现，是查代码时才定位的）。
3. `VectorCollections.nameFor` 是集合名唯一来源，别处不要拼 `_dim` 后缀。
4. 检索缓存键必须带可见性签名与库级覆盖签名，否则跨角色/跨配置串号。

**会让评测数字漂移的（可复现三前提，缺一个就漂）**

1. **同分排序破平**：RRF 候选按 chunkId、全文 SQL 按 id、本地向量按 id。`HashMap` 迭代序不稳定，同分必须显式 `thenComparing`。
2. **夹具串行摄取**：并发上传会让分块 id 交错，破平按 id → 名次漂移。
3. **每轮 `OPTIMIZE TABLE agent_kb_chunk`**：InnoDB 全文索引对已删行异步清理会改 IDF，实测让 MRR 每轮漂 0.01~0.02，是"数字对不上"的真因。生产不需要，仅测量夹具需要。

**真 bug 级踩坑**

- `TextChunker` 拼重叠尾段时丢了块间换行 → 第二块在父块里不是连续子串 → 父子偏移定位必然失败。**修分块器而非修定位**。
- 切代读竞态：先读分块（旧代）再读文档指针（新代），中间恰逢切指针 → 全部判过期得空结果。同 id 重读救不回来（新代是另一批 id），必须整次重跑并绕过缓存。
- PDF 图片可能藏在 Form XObject 里，只扫页面直属资源会漏图（pymupdf 导出的 PDF 就是这种）；`getXObjectNames()` 返回 `Iterable` 无 `size()`。
- 实体 `vlm_enabled` 是 `Integer`，判定必须 `Integer.valueOf(1).equals(...)`；用 `Boolean.TRUE.equals(...)` 恒为 false，表现为"开关开着但标记被静默剥离"。
- MySQL 无 `ADD COLUMN IF NOT EXISTS`：幂等脚本用 `information_schema` 判断 + `PREPARE/EXECUTE`，否则重复执行报错退出。
- `agent_kb_chunk` 无 `update_time` 列，补偿扫描只能基于 `create_time`。

**工程口径**

- **19530 是 gRPC 不是 REST**：milvus-lite 3.x 只实现 gRPC，用 HTTP/1.1 打 `/v2/vectordb/...` 会拿到 HTTP/2 SETTINGS 帧。Java 侧统一 `MilvusClientV2`，别再写 `RestClient` 调 REST v2；SDK 自带 shaded gRPC/Netty，protobuf-java 由父 pom 锁 4.36.2。
- **切换 `vector-store` 不会自动回填**：补偿任务只捞 `PENDING/FAILED`，存量 `SYNCED` 分块在新后端里没有向量，语义召回会静默为空。切后端后执行一次 `backend/sql/manual_vector_backend_resync.sql`（非幂等，仅切时跑），或对每个库 `reindex`。

- 新增 Vo **必须挂 `@AutoMapper(target = Entity.class)`**，否则 `selectVoPage` 抛 ConvertException 而接口只回"未知异常"。
- 评测数字以 `baseline/` 的 `kb_baseline.py` 为准，`--no-write` 只打印不覆盖。**改语料或用例等于改基线**，必须重跑存档为新版本号。
- MySQL `NATURAL LANGUAGE MODE` 只有一行时所有词算高频被忽略 → 关键词路零命中，造数至少 2 个分块。
- 「0 = 关闭」用独立 boolean 开关，不用 `fixedDelayString=0`（会抛异常导致应用起不来）。

---

## 4. 数据与配置

**表**（增量脚本 `backend/sql/agent_kb_*.sql`，均幂等；Docker init `01-schema.sql` 已同步）

| 主要表 | 用途 |
| --- | --- |
| `agent_knowledge` | 知识库：分块参数、`clean_rules`、`chunk_unit`/`split_strategy`、双指纹、`retrieval_overrides`、`vlm_enabled`、`review_required`、`hide_parent_with_disabled_child`、`fallback_answer_enabled` |
| `agent_knowledge_document` | 文档：`active_generation`、`config_stale`、`parse_fingerprint`、`parsed_text`、`visibility`、`publish_status`、生效窗口、`trashed`、`notice` |
| `agent_kb_chunk` | 分块：`generation`、`vector_status`/`vector_retry_count`、`embedding`/`embedding_dim`、`parent_offset_*`、`heading_path`/`page_no`/`sheet_name` |
| `agent_kb_query_log` | 检索日志（`query_hash` 归一化、各路召回数、降级、耗时） |
| `agent_kb_eval_case` / `agent_kb_eval_run` | 评测用例（`evidences` span）与运行（`corpus_fingerprint`、`config_snapshot`、`case_results_json`） |
| `agent_kb_quality_issue` | 质量问题 3 态 |
| `agent_kb_doc_acl` | 文档级 ACL |
| `agent_kb_vlm_config` | 工作空间级 VLM 配置（Key 加密） |
| `agent_kb_kg_entity` / `agent_kb_ext_source(_item)` | 图谱映射 / 外部源登记 |

**配置**（`rag.knowledge.*`，完整项见 `KnowledgeProperties`）

| 组 | 关键项 |
| --- | --- |
| 向量 | `vector-store`(milvus/mysql)、`milvus-host/port/token/database/secure`、`milvus-consistency-level`(dev STRONG / prod BOUNDED)、`collection-name`、`vector-dim`（兜底）；SDK 3.0.12 gRPC |
| 检索 | `rag-min-score`、`rag-max-hits`、`cross-kb-rrf-k`(60)、`rewrite-timeout-ms`(2000)、`rerank-timeout-ms`(1500)、`rerank-candidate-limit`(50)、`rerank-mode` |
| 补偿 | `sync.compensation-enabled`(true)、`batch-size`(200)、`max-retry`(5)、`pending-timeout-minutes`(5)、`compensation-interval-ms`(30000) |
| 门禁/校验 | `gate.required`(**false**，先观察)、`citation.validate-enabled`(true) |
| 图谱/外部源 | `kg.extract-enabled`(**false**)、`kg.extract-model`、`ext.*` |
| VLM | `vlm-enabled`(**false**)、`vlm-model`、`secret-key`（**上线必须替换**，换密钥后既有密文需重存） |

---

## 5. 明确不做（附依据）

| 不做 | 依据 |
| --- | --- |
| Elasticsearch / Qdrant / Neo4j | 目标规模单库 ≤10 万分块，MySQL + Milvus 足够；多三个中间件多三份运维与一致性负担。**关键词路质量先治词表，不够再上可选 ES profile** |
| 独立解析微服务 | 参考项目拆是为了跑 Tesseract/Playwright 重依赖。先做进程内超时 + 资源上限，出现"大文件打死主进程"的真实证据再拆 |
| LLM 语义分块 | 每篇数十次模型调用，成本与延迟不可控；且必须先有可信评测才能判断值不值 |
| 完整文档版本表 + 回滚 | `generation` 已拿走零停机重建与失败可退；版本历史是内容审计需求，等提出再做 |
| 多租户 / SSO / 注册审核 / 操作审计 | 与 RAG 效果无关；已有 Sa-Token + workspace + AuditLogHelper |
| 每知识库独立 Milvus collection | 千库千集合拖垮元数据；单集合 + filter 更省。**重新评估条件：单库 >10 万分块或集合 >500 万行** |
| A/B 灰度、Prompt 实验平台 | 检索效果靠评测门禁，不靠线上分流 |
| 置信区间参与门禁 | 与 ε 两套噪声控制并存会让判决不可解释 |
| Tesseract OCR（默装） | tess4j + 语言包体积大、中文质量依赖数据包；改走 VLM 描述，OCR 留可选 profile |

---

## 6. 与 kb-rag 的差距与下一步

R1~R5 交付后，分数口径、阈值语义、跨库 RRF、降级回执、评测五指标、门禁双跑、引用校验、文档 ACL、图谱第三路这些**已对齐**。剩下的差距不在功能数量，在三处下限。

### 6.1 关键词路的中文召回能力（优先级 1）

| | kb-rag | ForgeAgent |
| --- | --- | --- |
| 引擎 | Elasticsearch + ik 词级分词（含自定义词典） | MySQL `FULLTEXT ... WITH PARSER ngram` |
| 排序 | BM25 | `MATCH ... AGAINST` 自然语言分 |
| 粒度 | 词（可配词典、同义词） | 2-gram 滑窗 |

ngram 2-gram 对中文专有名词、短查询、同义表达召回偏弱。更麻烦的是这一路本身不稳：InnoDB 全文索引异步清理改 IDF，同语料 MRR 每轮漂 0.01~0.02（见 §3）。**一路会漂移的召回会污染所有调参信号**。阈值、精排、RRF 都是在召回结果上排序，召回漏了后面全空。

**建议**：先做词表治理（自定义词典、停用词、同义词表，落到 MySQL 分词层或预处理层），成本最低见效最快；再评估可选 ES 部署，做成 `vector-store: milvus|mysql` 那样的开关，不改单体架构假设。同时把关键词路可复现性写进基线，别让漂移混在指标里。

### 6.2 摄取可信度：解析预览确认 + 扫描件（优先级 2）

kb-rag 有 `DocumentPreviewService` + `PENDING_CONFIRM` 状态机，解析产物先给人看、确认后才进索引；扫描件走 PaddleOCR 三级兜底。ForgeAgent 解析硬化做了（魔数、zip 炸弹、XXE、超时、乱码），清洗也做了，但**解析完直接进索引，没有确认环节**；OCR 未做，纯扫描页只能靠 VLM 描述兜底。

坏解析的污染是静默的，分块位置错、表格切断、字符乱码都要等检索命中异常才暴露。RAG 的下限由输入决定，装进去的内容本身是错的比排序不好更难查。

**建议**：加解析预览确认，做成开关（默认关，重要库或新接入来源开启），状态机沿用 `PARSED → PENDING_CONFIRM → INDEXING`；OCR 按 §5 做可选 profile。

### 6.3 检索洞察与内容缺口分析（优先级 3）

kb-rag 有 `SearchInsightService` + 洞察/统计端点（query_hash 归一化分组、零命中率、Top 未命中 query、内容缺口报表）。ForgeAgent 的 `queryHash` 已落库（`agent_kb_query_log`、`agent_kb_quality_issue` 都有），零命中日志、badcase、质量问题 3 态也都在，但**只有明细没有聚合视图**。

R3 的评测回答"这次改配置好没好"，回答不了"用户在问什么而我们答不了"。后者才决定该补哪份文档。

**建议**：基于 `agent_kb_query_log` 加聚合报表（零命中率趋势、Top 未命中 query、按库/时间分组），复用现有表不动模型。可选再加"从零命中 query 半自动生成评测用例"（kb-rag 也列在未排期项，难度不低，可后置）。

### 6.4 建议不追

多模态图片向量/以图搜图（VLM 描述已覆盖大部分图文混排）、员工工作台/应用发布快照（ForgeAgent 是 Agent 平台形态，对话/客服/工作流已有）、多租户 SSO（见 §5）——这些是形态差异或有意取舍，不是能力缺口。

**排期建议**：① 词表治理 + ② 解析预览确认都是小改动，能立刻提高线上可用性；③ 的聚合报表可与前两个并行。

---

## 7. 批次历史

| 批次 | 交付内容 | 状态 |
| --- | --- | --- |
| R1-A 检索内核 | D0 阈值只比可比分、D1 维度分片、D4 跨库 RRF、D5 阈值策略、D6 编辑顺序、D7 改写超时；`ScoreType`/`ScoreNormalizer`/`ScoreThresholdPolicy`/`VectorCollections` | 已交付 |
| R1-B 可观测 | `DegradedReason`(8) + `SearchOutcome` + `agent_kb_query_log`；精排 1500ms 硬超时 + 候选 50 + 专用池；Redis 异常降级 | 已交付 |
| R2-A 摄取一致性 | D2 `generation` 零停机重建、D3 `vector_status` + 补偿、R1-7 父子禁用子块挖除；修 `TextChunker` 丢换行真 bug | 已交付 |
| R2-1 解析硬化 | `DocumentParseGuard`/`GarbledTextDetector`/`HtmlDocumentParser`/`ParsedText`；扫描件标失败 | 已交付 |
| R2-2 清洗层 | `DocumentCleaner` + `CleanRules` + `KbPiiMasker`（顺序钉死） | 已交付 |
| R2-3 分块 | token 口径 + 策略可插拔 + `heading_path`；char 模式逐字回归护栏 | 已交付 |
| R2-5 指纹 | 双指纹 + 差异重建 + `parsed_text` 产物复用 | 已交付 |
| R3 评测闭环 | `SpanHitJudge`/五指标+Wilson/`GateJudgeCore`/质量问题 3 态/case-from-hit；`CorpusFingerprints` | 已交付 |
| R4 问答可信 | `buildReferenceBlock` + `AnswerCitationValidator` 硬门禁 + 拒答策略 + `agent_eval` 五维裁判 | 已交付 |
| R5-1/2 治理 | `DocVisibilityResolver` + 文档 ACL 检索前下推 + 审核/有效期/回收站 | 已交付 |
| R5-3/4/6 图谱与外部源 | `KbGraphRetriever` 图路、`service/ext/` SPI + URL 抓取（S3/Confluence 骨架）、库级 `retrieval_overrides`、MCP filters | 已交付 |
| R5-5 图片 | 图片抽取 + VLM 描述进正文 + 工作空间级 VLM 配置（Key 加密） | 已交付 |
| 2026-11-14 Milvus 3.0 统一 | dev/prod 统一 gRPC 客户端（SDK 3.0.12）；compose 升 v3.0.6 + MinIO + etcd 3.5.25；后端切配置即换环境；新增 15 条向量契约/真机用例 | 已交付 |
| 剩余 | S3/Confluence 真实接入（SPI 就绪，实现 `fetch` 即可）、OCR 可选 profile、图片向量 | 按需 |

> 各批的单测与 e2e 数字已随验证环境一并归档；回归命令见 [backend/README](../backend/README.md)。
