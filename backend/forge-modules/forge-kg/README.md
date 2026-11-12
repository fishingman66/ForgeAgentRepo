# forge-kg 知识图谱模块

> P3 / M3 · 2026-11-10 抽成独立模块（用户决策：图谱不只服务模型记忆，还要为智能客服提供领域能力）

## 定位

把「实体—关系」图谱能力收敛成一个零业务依赖的模块，供多个业务域复用：

| 业务域 | 范围 | 典型用途 |
| --- | --- | --- |
| 长期记忆（forge-memory） | `KgScope.agent(agentId, userId)` | 把对话沉淀的离散事实连起来，注入时补充「关联背景」、标注矛盾说法；L5 用户遗忘级联 `deleteScope` |
| 智能客服（forge-customer-service） | `KgScope.workspace(workspaceId, null)`（落库 `userId=0`） | 领域知识（产品、故障、话术、意图关系）的人工维护与召回；`CsKgQueryExpander` 检索词扩展 |
| 知识库 / RAG（forge-rag） | 分块来源映射 `agent_kb_kg_entity` | R5-3 图谱第三路：文档换代后异步抽实体/关系，检索时种子实体 ≤2 跳扩展，只贡献 RRF 名次分 |
| 其他域 | `KgScope.of(类型, id, userId)` | 渠道级知识等，接口不限定 |

依赖方向：`memory → kg`、`customer-service → kg`、`rag → kg`，三个业务域互不依赖；`kg` 不反向依赖任何业务模块（只依赖 `forge-common-core` / `forge-common-mybatis`）。

**20 个单元测试 / 2 类**（`KgServiceTest` 9 + `MysqlKgStoreTest` 11），全部 `@Tag("dev")`。

## 结构

```
com.forgeagent.kg
├── config/KgProperties        # kg.* 配置
├── domain                     # KgNodeEntity / KgEdgeEntity（仅 MySQL 实现）
├── mapper                     # KgNodeMapper / KgEdgeMapper（幂等 upsert SQL）
└── service
    ├── KgScope                # 范围三元组 (scope_type, scope_id, user_id)
    ├── KgTriple / KgNode / KgEdge
    ├── KgStore                # 存储后端契约（SPI）
    ├── KgService              # 门面：开关、选后端、写入收口、召回降级、冲突判定
    ├── KgText                 # 实体/关系归一化口径（所有后端必须共用）
    ├── KgConflictPairs        # 反义关系词表
    └── impl/MysqlKgStore      # MySQL 两表实现（开发/测试默认后端）
```

## 后端可插拔（Neo4j 接口）

开发/测试环境用 MySQL 两表存图（零新基建）；生产规模上来时**新增一个实现类即可**，
`KgService` 从容器里的 `List<KgStore>` 按 `kg.backend` 自动选中，调用方一行不改：

```java
@Service
public class Neo4jKgStore implements KgStore {
    @Override public String backend() { return "neo4j"; }
    @Override public void upsert(KgScope scope, List<KgTriple> triples) { /* Cypher MERGE */ }
    @Override public List<KgEdge> neighbors(KgScope scope, List<String> texts, int limit) { /* MATCH */ }
    @Override public List<KgEdge> edges(KgScope scope, int limit) { /* ... */ }
    @Override public List<KgNode> nodes(KgScope scope, int limit) { /* ... */ }
    @Override public boolean removeEdge(KgScope scope, Long edgeId) { /* ... */ }
    @Override public void deleteScope(KgScope scope) { /* ... */ }
}
```

实现约定：

1. 归一化统一走 `KgText`（唯一键 = 去标点小写），否则换后端等于图谱重建；
2. `upsert` 幂等：同一 (范围, 用户, 主语, 关系, 宾语) 只累加权重，不产生重复边；
3. 读范围含共享级（`user_id=0`），否则共享关系永远召不回；
4. 允许抛异常——`KgService` 统一降级（写入 warn、召回空列表），图谱故障不阻断调用方主流程；
5. 未实现的后端（配了没有对应 Bean 的 `kg.backend`）按**关闭**处理并告警一次，绝不半启用。

## 数据模型（MySQL）

`kg_node` / `kg_edge` 两表，见 `backend/sql/forge_kg_core.sql`（幂等，可重复执行）：

- 范围三元组 `(scope_type, scope_id, user_id)`，`user_id=0` 表示范围共享级
  （**不用 NULL**：MySQL 唯一索引对 NULL 不去重，会让共享节点重复插入、upsert 失效）；
- 唯一键即 upsert 冲突键：
  `node(scope_type, scope_id, user_id, name_key)`、
  `edge(scope_type, scope_id, user_id, source_key, relation, target_key)`；
- `kg_edge.evidence_ref` 为通用溯源引用（记忆 ID / 工单 ID / FAQ ID），不做外键；
- `removeEdge` 只删与 scope 完全同范围、同共享级的行，跨范围/跨用户一律拒绝。

## 接入方式

**写入（记忆域自动抽取）：**

```java
// 抽取线程里,三元组在组装前由业务域完成 PII 打码(forge-kg 不依赖业务脱敏器)
kgService.ingest(KgScope.agent(agentId, userId),
    List.of(new KgTriple("ForgeAgent 项目", "使用", "MySQL", memoryId)));
```

**召回：**

```java
// 记忆注入:用入选记忆文本反查实体,拿 1 跳邻接 + 冲突标注,渲染成「关联背景」
List<KgEdge> edges = kgService.neighbors(KgScope.agent(agentId, userId), selectedContents, 5);
Set<String> conflicts = kgService.conflicts(edges);

// 客服:访客问题进来直接拿结构化关系,喂给坐席辅助或答案生成
List<KgEdge> domain = kgService.neighbors(KgScope.workspace(workspaceId, null), List.of(question), 5);
```

**维护端点（客服域，`forge-customer-service`）：**

| 端点 | 说明 |
| --- | --- |
| `GET /agent/customerservice/knowledge/graph/relations` | 领域关系列表（权重降序，带冲突标注） |
| `GET /agent/customerservice/knowledge/graph/conflicts` | 冲突盘点：反义关系两两一组，审核=删错误侧 |
| `POST /agent/customerservice/knowledge/graph/relations` | 手工登记三元组（幂等 upsert） |
| `DELETE /agent/customerservice/knowledge/graph/relations/{id}` | 删除一条关系（跨范围拒绝） |
| `GET /agent/customerservice/knowledge/graph/nodes` | 空间实体 TOP |

前端维护页：`frontend/src/views/customerservice/KnowledgeGraph.vue`（菜单「智能客服 → 知识图谱」）。

**检索扩展（客服域消费图谱的另一种方式）**：`CsKgQueryExpander`（forge-customer-service）把访客
文本命中的实体扩展成邻接实体词拼进知识检索 query，开关 `agent.customer-service.kg-expand-enabled`
（默认关闭）。扩展发生在客服侧而非本模块——本模块保持零业务依赖，只提供 `neighbors` 原料。

范围由登录坐席所属空间解析（`CsWorkspaceResolver`），不接收 workspaceId 参数，防跨空间读写。

## 配置

```yaml
kg:                          # 图谱本体（forge-kg）
  enabled: true              # 总开关,默认 false
  backend: mysql             # mysql / neo4j(预留,未实现按关闭处理并告警一次)
  scan-node-limit: 500       # 「文本→实体」匹配的节点扫描上限
  max-triples-per-ingest: 6  # 单次写入三元组上限
  min-edge-weight: 1.0       # 召回的最低边权重
  # 反义关系对配置扩展(冲突判定),每项「A:B」;内置词表见 KgConflictPairs
  extra-conflict-pairs:
    - '解决:导致'
    - '开通:关闭'

memory:                      # 记忆侧自用参数（forge-memory）
  graph:
    inject-chars: 220       # 「关联背景」字符预算,从记忆块总预算里扣
    neighbor-limit: 5       # 单次注入的邻接边上限
    rebuild-batch: 10       # 存量补建每批送模型的记忆条数
    rebuild-limit: 200      # 存量补建单次处理上限
```

## 已知边界

- 文本→实体匹配是「压平后包含匹配」，刻意不做分词（实体名本身就是抽取出的短词，中文场景零依赖够用）；压平后长度 < 2 的实体不参与匹配，避免单字误命中。
- 冲突判定是确定性词表（`KgConflictPairs` + `kg.extra-conflict-pairs`），不做语义推断。
- **M4 已交付**：记忆删除 → 边减权 / 节点半衰、用户级遗忘（`DELETE /agent/memory/user/{userId}/forget?confirm=FORGET`）级联 `deleteScope` 清图谱，配合归档表与配额淘汰一起完成 L5 治理。
- R5-3 图谱第三路的**抽取与打分逻辑在 `forge-rag`**（`service/graph/`），本模块只提供 `ingest` / `neighbors` 原料，保持零业务依赖；抽取开关 `rag.knowledge.kg-extract-enabled` 默认关。
- 实体类型暂统一 `entity`，类型细分等出现真实检索需求再做；Neo4j 后端实现仍待按需接入（SPI 已就绪）。

## 测试

```bash
mvn -Pdev test "-Dtest=KgServiceTest,MysqlKgStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dmaven.test.skip=false" "-DskipTests=false" -pl forge-modules/forge-kg -am
```

覆盖：门面（开关 / 后端选择 / 写入收口 / 召回降级 / 冲突判定 / 共享级读取）9 用例、MySQL 存图（幂等 upsert / 唯一键 / 跨范围删除拒绝 / 权重累加）11 用例。验收以 surefire 报告的 `tests/failures/errors` 为准，不认 EXIT=0。
