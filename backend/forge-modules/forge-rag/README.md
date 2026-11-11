# forge-rag · RAG 检索增强模块

知识库文档摄取、向量化、混合检索、重排序、检索评测与 MCP 检索端点。
依赖方向：`forge-agent-core ← forge-rag ← forge-agent`（单向，无环）。

## 架构与数据流

```
摄取链: 文档上传 → 解析(pdf/docx/txt/md/csv/json/xlsx) → 结构感知分块
        (章节路径前缀 / 表格保表头 / 代码原子块 / 中文句读边界)
        → [可选父子分块: 先切父块再切子块]
        → 嵌入(OpenAI兼容 / local-hash 兜底,分批+超时+退避重试)
        → MySQL 子块落库 + 向量(milvus REST v2 / mysql 向量列,可配)

检索链: 双路召回(向量 + FULLTEXT ngram,父块不参与)
        → 禁用分块过滤 → RRF 融合(k=60) → 重排序精排(可配)
        → 阈值过滤(向量/精排分离配置) → 结果缓存(60s) → 命中统计
        → 子块回填父块全文 + 页码定位标记

降级设计: 向量库挂 → 关键词兜底;Milvus 集合未加载 → 自动 load 重试;
          重排失败 → RRF 回退;向量写失败 → 不阻断解析;
          嵌入网络失败 → 1s/2s 退避重试 3 次
```

## 向量存储：Milvus 与 MySQL 本地版

`VectorStore` 是唯一抽象，检索链路（`AgentKnowledgeServiceImpl` / `KnowledgeDocProcessorImpl` / 附件索引）全部注入接口，换实现不动业务代码。

| 值 | 实现 | 适用 | 说明 |
| --- | --- | --- | --- |
| `milvus`（默认） | `MilvusVectorStore` | 生产 | REST 调 Milvus，AUTOINDEX + COSINE；集合启动时自动创建 |
| `mysql` | `MysqlVectorStore` | 本地开发 | 向量存 `agent_kb_chunk.embedding`（float32 小端 BLOB），检索在应用层算余弦 |

```yaml
rag:
  knowledge:
    vector-store: mysql   # dev 默认；生产保持 milvus
```

**为什么有这个实现**：开发机不装 Milvus 时检索恒为空 → 对话永远走兜底，连「知识库有没有生效」都验证不了。MySQL 版让一个数据库撑起全链路。

**边界（改这里之前先看）**：

- 检索是 O(n) 全扫，单次最多扫 5000 分块（超出会告警并截断）。几千分块约几十毫秒；**上万分块就该上 Milvus**。
- 向量维度与 `vector-dim` 不一致的分块会被跳过——换 embedding 模型后老向量自动失效，不会算出错误分数。
- `embedding` 字段标了 `@TableField(select = false)`：默认查询不带出 BLOB，否则分块列表上千行会把响应撑爆；检索时用显式 select 取。
- 升级脚本 `backend/sql/agent_kb_local_vector.sql`（幂等，只加两列）。

**配合 `local-hash` 嵌入**：知识库 `embedding_model` 留空即走 `LocalHashEmbeddingService`，连 embedding API 都不用。注意它是特征哈希伪向量，**只能验证链路通不通，不能用来评估召回质量**。

## 目录说明

```
src/main/java/com/forgeagent/rag
├── config/           # KnowledgeProperties(rag.knowledge.*) / 异步线程池
├── controller/       # 知识库 / 文档 / 评测 / MCP 端点
├── domain/           # 知识库 / 文档 / 分块(含 chunk_role/parent_id/page_label) / 评测表实体
├── mapper/           # 知识库 / 文档 / 分块 / 评测表 Mapper
├── service/
│   ├── document/     # 解析器(pdf/docx/txt/md/csv/json/xlsx) + 结构感知分块器
│   ├── embedding/    # 嵌入服务(OpenAI 兼容 + local-hash 演示) + 工厂
│   ├── vector/       # VectorStore 抽象 + Milvus 实现 + MySQL 本地实现 + VectorCodec + Schema 初始化
│   ├── rerank/       # 重排序(标准 Reranker API / LLM 打分双路由)
│   └── impl/         # 知识库 / 文档 / 摄取管线 / 评测服务
└── (src/test)        # MCP 端点协议一致性 15 + 向量编解码 5 = 20 用例
```

## 关键配置(rag.knowledge.*)

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| milvus-host / milvus-port / milvus-token | 127.0.0.1 / 19530 / 空 | Milvus 连接 |
| collection-name / vector-dim | agent_kb_chunk / 384 | 向量集合 |
| file-dir / max-file-size-mb | ./data/knowledge / 20 | 文档存储 |
| rag-top-k / rag-max-hits / rag-min-score | 3 / 5 / 0.05 | 对话检索参数 |
| query-rewrite-enabled | true | 多轮查询改写 |
| rag-context-budget | 2000 | 注入上下文 token 预算 |
| rag-neighbor-expand | true | 邻块扩展(父子分块的子块优先用父块全文) |
| rag-cache-enabled / rag-cache-ttl-seconds | true / 60 | 检索结果缓存 |
| **mcp-enabled** | **false** | MCP 检索端点开关(默认关闭) |
| **mcp-token** | 空 | MCP 端点访问令牌(空=不校验,仅建议内网) |

## 父子分块(small-to-big)

知识库编辑页开启「父子分块」并设置父块大小(默认 2000 字符)后：

- 摄取：先切父块（不嵌入、不召回），再在父块内切子块（嵌入+召回）
- 检索：命中的子块回填 `parentContent`（父块全文）
- 注入：对话上下文优先使用父块全文（受同一 token 预算约束）

注意：开关只影响新摄取的文档；存量知识库需「重建索引」一次。

## 重排序双路由

知识库配置「重排序模型」（agent_model 配置名称）后自动路由：

- 模型名**含 `rerank`**（bge-reranker / gte-rerank / qwen3-reranker 等）→ 走标准
  `POST {base}/v1/rerank` API（兼容硅基流动/百炼/Jina/Cohere），一次调用全量打分，秒级返回
- 其他模型 → LLM 逐条打分（候选截断 20 条控制成本），30s 超时，失败回退 RRF 粗排

## 检索评测集

- 用例：`POST /agent/kb/eval/case`（问题 + 期望命中文档名 + 期望关键词）
- 运行：`POST /agent/kb/eval/run?knowledgeId=&topK=` —— 逐用例跑检索，按期望判定命中，
  统计命中率与 MRR（平均倒数排名）落库，返回逐用例明细
- 历史：`GET /agent/kb/eval/runs?knowledgeId=&limit=`
- 用途：调整 chunkSize / 阈值 / 重排模型后对比命中率，让调参有数据依据

## MCP 检索端点

将知识库检索暴露为 MCP 工具，供 Claude Desktop / Cursor 等客户端调用。

**启用**：`rag.knowledge.mcp-enabled: true`（默认关闭），建议同时配置 `mcp-token`。
安全：sa-token 已放行 `/mcp/**`；令牌非空时客户端须带 `X-MCP-Token` 头（常量时间比较）。

**客户端接入**（以 Cursor 为例）：

```json
{
  "mcpServers": {
    "forgeagent-knowledge": {
      "url": "http://<host>:8080/mcp",
      "headers": { "X-MCP-Token": "<your-token>" }
    }
  }
}
```

**工具**：
- `knowledge_search(query, knowledgeId?, topK?)` —— 检索文档片段，
  返回 knowledge/file/location(page=3 或 sheet=表名)/score/content，
  2025-06-18 协议下附带 structuredContent
- `knowledge_list()` —— 列出启用的知识库，用于获取 knowledgeId

**协议能力**：Mcp-Session-Id 会话（30min TTL）、版本协商（2025-06-18 / 2025-03-26）、
JSON-RPC 批处理、structuredContent、规范错误码（-32700/-32600/-32601/-32602）、
GET/DELETE 405。限制：无 SSE 响应（回纯 JSON）、无服务器发起能力（sampling/进度通知）。

## 相关 SQL

- 增量脚本（MySQL）：`backend/sql/agent_kb_parent_chunk.sql`（父子分块）、
  `agent_kb_page_meta.sql`（页码定位）、`agent_kb_rerank_threshold.sql`（精排阈值）、
  `agent_kb_eval.sql`（评测两张表）
- Docker 路径：`deploy/docker/mysql/init/01-schema.sql` 已同步全部列与评测表

## 测试

MCP 协议一致性测试套件（15 用例，见 backend/README「模块测试」）：
安全默认值 / 握手与会话 / 版本协商 / 工具发现与调用 / 错误脱敏 / 协议错误码 / 通知与批处理 / 405。
