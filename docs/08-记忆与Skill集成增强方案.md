# 记忆与 Skill 集成增强方案（对标 DeepSeek Harness）

> 2026-11-15 立项；同日按定位适配评审修订优先级。
> 对标对象：DeepSeek Harness（DSH，2026-08 开源，MIT，Cordis 插件框架）。
>
> **核心结论：不全照搬。** DSH 是单用户本地编程工作台，ForgeAgent 是多租户企业平台，场景不同。本方案只取与企业定位匹配的部分：
> - **首批（真痛点小切口）**：A4 MCP 连接池 + B3 记忆关键词通道 + C4 L1 沉淀；
> - **条件项（满足触发条件再启动）**：Skill 体系 A1/A2/A3、MCP Server 泛化 A5；
> - **等需求（暂不做）**：B1/B2/B4/B5、C3 会话分叉。

---

## 1. 对标对象：DSH 的三条主线

| 主线 | DSH 做法 | 一句话本质 |
|---|---|---|
| 插件树 | 一切皆插件，profile→bundle→patch 装配；注册返回 disposer（LIFO 回收）；无特权内核 | 能力怎么装配 |
| Session Log | 会话是 append-only 事件日志，唯一事实源；模型历史从日志投影（deriveMessages）；compaction 在窗口占 80% 时自动触发；超大工具输出 spill 落盘为 0700 文件 + head/tail 预览 + locator | 事实怎么记 |
| Capability Seam | 每个能力 = Service Definition + Provider + Consumer 三角色；换 Provider 不动调用方 | 能力怎么替换 |

DSH 记忆生态的两个关键设计：

- **Skills 懒加载**：技能在上下文里只占一行摘要目录，模型按需调用加载全文——解决「工具描述撑爆上下文」这一 tool-heavy Agent 的真实瓶颈。
- **跨会话记忆交给插件/MCP**（Mem0/Hindsight/memory-git 等），内置部分只管单会话日志 + 压缩。社区 graph-memory 插件补了 PageRank / 社区检测 / 多跳图召回。

DSH 的教训（我们不学或反着学的）：

- Host 插件 = 无沙箱任意代码执行，第三方插件信任成本极高；
- 会话分叉继承 seed、session-query FTS5 全文索引，是好东西但**属于个人开发者场景**；
- 内置 compaction 有损且跨会话即失——纯内置记忆不够，但 ForgeAgent 本来就有 L3，这不是我们的短板。

## 2. 定位适配：哪些 DSH 能力与 ForgeAgent 场景错配

DSH 与 ForgeAgent 的场景差异是本方案取舍的总依据：

| 维度 | DSH | ForgeAgent |
|---|---|---|
| 用户 | 单开发者，本地运行 | 多租户企业平台（客服/呼叫中心/知识库问答） |
| 工具量 | 几十个 MCP/工具，描述撑爆上下文是常态 | `tool_ids` varchar(500)，单 Agent 绑定十几个 |
| 会话性质 | 编程实验，需要 fork/replay | 业务对话，消费者是终端访客与坐席 |
| 信任模型 | 用户自己装插件，自负风险 | 租户不可注入代码 |
| 记忆消费 | 开发者翻历史找决策 | 访客无感，坐席辅助走 A 系列 |

由此得出的判定：**「工具描述膨胀」「会话分叉」「本地全文索引」在 ForgeAgent 当前形态下不是真痛点**；「MCP 重连」「中文召回」「run 结束信息归零」是。

## 3. 设计决策

**决策一：不做可执行插件。** DSH 插件是全权限 Node 代码，信任边界靠「用户自己装」兜底；ForgeAgent 是多租户平台，Java 侧 SPI 无进程隔离，等价于把任意代码执行开放给租户。因此：
- Skill = **声明式包**（Markdown 操作手册 + 工具/知识库/提示词片段引用），由平台解释执行；
- 扩展点收敛到 **Java SPI**（ToolProvider / MemoryProvider），由平台开发者实现，租户只能组合不能注入代码。

**决策二：记忆六层骨架保留。** L0 预算分配、L1 工作记忆、L2 摘要、L5 治理与 DSH 对标后不落后，问题在通道与完整性，做增量且只做有消费者的部分。

**决策三：Skill 体系是条件项，不是默认项。** 「工具描述撑爆上下文」在当前工具绑定量下不成立；Skill 的真实价值是「多 Agent 共享操作手册」，**满足触发条件才启动**（见 4.2 触发条件）。

**决策四：架构对齐类改造（SPI 化、append-only）一律等需求。** 没有消费者的抽象是负债，不做预防性重构。

## 4. 分期方案

### 4.1 首批：真痛点小切口（无前置条件，三项互不依赖可并行）

> **实施状态（2026-11-15）**：三项已完成编码与单测。
> - ✅ A4 已交付：新增 `McpConnectionPool`（Caffeine,指纹=serverType|url|超时|排序 headers;命中后 listTools 兼作健康校验,失败驱逐自愈;`agent.chat.mcp-pool-enabled` 可回退），`ToolAssembler` 池化接入;测试 `McpConnectionPoolTest` 6 条 + `ToolAssemblerMcpPoolTest` 3 条全绿。
> - ✅ B3 已交付：`agent_p2_memory_keyword.sql`（FULLTEXT ngram 幂等 DDL,临时库真机验证两连跑 + 中文冒烟通过）;`AgentMemoryMapper.searchByKeyword`;`buildMemoryBlock` 三路融合(α·语义+β·评分+γ·关键词,γ 默认 0.4 保证 FTS 前 2 名压得过评分池;索引未建自动降级+按错误去重告警);测试并入 `AgentMemoryServiceImplTest`。
> - ✅ C4 已交付:`RunWorkingMemory` 取回计数 + `collectHotEntries`;`IAgentMemoryService.ingestWorkingMemory`(去重+配额+向量化);`AgentChatServiceImpl` 阻塞与 SSE 两条 run 收尾路径接入;`agent.chat.working-memory-persist-*` 配置;测试 `RunWorkingMemoryPersistTest` 5 条 + 沉淀用例 3 条。

**A4 MCP 连接池**（复杂度：低-中，纯性能修复）
- 现状：`ToolAssembler` 每请求构建 Toolkit，MCP 每请求重连（初始化上限 30s）。
- 改动：`McpClientWrapper` 按 `serverId` 缓存复用（Caffeine + 空闲过期 + 健康探活），失败重建；workflow 路 `buildForWorkflow` 共用同一池。
- 验收：同一 run 多次工具调用只建一次连接（计数断言）；断连自愈；须确认 wrapper 线程安全后再缓存，否则退化为会话级缓存。

**B3 记忆关键词通道**（复杂度：低-中，中文召回痛点，与 RAG 域踩坑同源）
- 现状：L3 仅评分×向量融合，中文短事实（「用户姓张」类）纯向量召回弱。
- 改动：`agent_memory.fact_key`/`content` 建 ngram 全文索引；`buildMemoryBlock` 召回改三路：评分 + 向量 + 关键词，融合公式扩为 `α·向量 + β·归一化评分 + γ·关键词命中`（权重入 `MemoryVectorProperties`）。
- 复用：RAG 域 `AgentKbChunkMapper#searchByKeyword` 经验；注意 `<script>` 必须顶格开头（前置换行静默降级为裸 SQL）的坑。
- 验收：中文短事实纯关键词路径命中；向量关闭时降级链完整。

**C4 L1 溢出沉淀**（复杂度：低，客服/呼叫中心场景真实损失）
- 现状：run 结束 `RunWorkingMemory.clear()`，高频取回的工具输出信息归零。
- 改动：run 结束时把取回次数超过阈值的 L1 条目择优异步写入 L3（走既有 `extractAsync` 通道，带来源标记）。
- 验收：沉淀条目可在下一会话 `buildMemoryBlock` 命中；不重复沉淀（按指纹去重）。

### 4.2 条件项：满足触发条件再启动

> **实施状态（2026-11-15）**：A1/A2/A3 已交付（用户拍板启动，触发条件视为成立）；A5 未启动（等产品决策）。
> - ✅ A3 已交付：表征测试 9 条先行锁定装配行为 → `ToolProvider` SPI（`type()`/`provide()` 返回列表）+ `HttpToolProvider`/`SandboxToolProvider`/`SqlToolProvider`；`ToolAssembler` 退化为按 toolType 路由；MCP 装配保留在 ToolAssembler（输入是 McpDef 且连接已由池组件承担，硬拆反而散）。新增 `ApprovalExempt` 标记接口保真「schema 只读工具免审批门」语义。表征测试复跑全绿，零行为变化。
> - ✅ A1 已交付：`agent_skill` / `agent_skill_install` 两表 DDL（菜单先删后插幂等，临时库真机两连跑通过）+ 实体/Bo/Vo/Mapper/Service/Controller 全套（`/agent/skill` 7 端点，权限码 `agent:skill:*`，菜单 1761400000000030161~66）。
> - ✅ A2 已交付：`load_skill` 内置工具（免审批门，白名单快照兜底 + 实时状态优先）+ system prompt 一行摘要目录（N 技能 ≈ N×40 token）；技能引用的工具/MCP 并入授权边界（安装即授权，路由统一裁剪）；`RunnerContext` 加 `skills` 字段全链路透传（6 处 build 调用）。
> - 验收：forge-agent 全量 surefire **128 tests / 0 failures / 0 errors**；`LoadSkillToolTest` 4 条 + 表征 9 条 + MCP 池 9 条 + 持久化 5 条。

**A1+A2 Skill 数据模型与懒加载**（复杂度：中）
- 设计（条件满足后按此实施）：
  - 新表 `agent_skill`：`name / description(一行摘要) / instructions(MEDIUMTEXT, md 手册) / tool_ids / mcp_ids / knowledge_ids / prompt_fragment / version / status / workspace_id`；`agent_skill_install` 技能到 Agent 的绑定（显式授权语义）。
  - `ToolAssembler` 装配时技能只把「一行摘要目录」追加进 system prompt（`[可用技能] name — description`），注册内置工具 `load_skill(name)`（套 `ApprovalGateTool`）按需加载全文；N 技能固定 ≈ N×40 token。
  - `config_hash`/发布门禁把技能绑定纳入版本快照，防止线上技能变更绕过发布流程。
- **触发条件（满足其一即启动，先补表征测试再动刀）**：
  1. 任一线上 Agent 工具 + MCP 工具绑定总数 > 20~30，出现上下文膨胀实测证据；
  2. 客服域明确出现「多个 Agent 共享同一套操作手册」需求（FAQ 流程手册、坐席辅助规程是最可能的首个消费者）。

**A3 工具装配 SPI 化**（复杂度：中，随 A1/A2 一起做）
- `ToolProvider` SPI：`String type()` / `List<AgentTool> provide(AgentToolDef def, BuildContext ctx)`；http/sandbox/sql/mcp 四类各抽一个 Provider，`ToolAssembler` 退化为按 `toolType` 路由到注册表。
- 启动时机与 A1/A2 绑定：Skill 装配需要新增第五类（skill 引用解析），届时一起 SPI 化，避免两次动 `ToolAssembler`。先行步骤：补 8 条表征测试锁住现有装配行为（含 MCP wrapper、审批门装饰顺序）。

**A5 对外 MCP Server 泛化**（复杂度：中，产品方向决策）
- `KnowledgeMcpEndpoint` 手写实现扩展为通用平台 MCP 端点：按 token 映射工作空间，暴露 `knowledge_search` + 白名单平台工具，使 DSH/Claude Code 等外部 Agent 可直连平台知识库。
- **触发条件**：出现对外开放/集成的产品需求（把 ForgeAgent 当企业 MCP 服务器提供）。技术储备已足（手写 Streamable HTTP 零 spring-ai-mcp-server 依赖，绕开 victools 冲突）。

### 4.3 等需求：有消费者再做（当前不做）

> **实施状态（2026-11-16）**：A5 已交付（用户拍板启动）。`McpExternalToolProvider` SPI（forge-rag 定义，forge-agent 实现 `SkillMcpExternalToolProvider`，对外暴露 `skill_list`/`skill_get`，server 名更新为 forgeagent-platform）；端点 18 tests + Provider 4 tests 全绿，forge-rag 199 / forge-agent 132 / 0 失败。
> **端到端验证（同日）**：本地库执行 `agent_skill.sql`；创建 2 个客服域技能（退换货处理 / 物流查询话术）并安装到「洪哥的智能体」；起服务走真实对话（Agnes 模型），验证链路全通——技能目录注入 system prompt（`promptHead=[可用技能]`）、`load_skill` 注册、**模型按手册步骤回复**（确认订单号→校验退货资格三要素→引导创建售后工单）。已恢复 Agent 停用原状。
> **踩坑**：`/auth/login` 必须 AES+RSA 加密（复用 temp/A1Login.java）；打包时运行中的进程锁 jar 需先停服务。
> **⚠️ 菜单号撞号（已修复）**：Docker 基线 02-seed 里 30161~30166 已被「长期记忆」菜单占用、30171~30176 被数据源管理占用——`agent_skill.sql` 首版 DELETE 段误删了本地库的记忆菜单（已恢复），技能菜单迁移至**空闲号段 30181~30186**；Docker 基线 01-schema 已补 agent_skill/agent_skill_install 两表（无 auto_increment）+ agent_memory 的 FULLTEXT ngram 索引折进建表定义，02-seed 追加技能菜单段（先删后插幂等），临时库 01+02 真机验证：记忆菜单 4 + 技能菜单 6 + 技能表 2 + FULLTEXT 2 列并存。**教训：新菜单号段必须先 grep 02-seed 查占用，不能默认 30161 起空闲**。

| 项 | 内容 | 等待的消费者 |
|---|---|---|
| B1 | L4 归档 append-only 化（归档改「标记+拷贝」，回放走主表 UNION 归档视图） | 回放/完整审计功能立项（L4 归档表已保留内容，主表 DELETE 目前只影响尚不存在的回放功能） |
| B2 | 会话全文索引 + `search_history` 工具 | 跨会话检索的真实消费者立项（坐席辅助 A 系列已覆盖现有诉求） |
| B4 | 图记忆 2 跳 + 度数/权重排序（对照 graph-memory 插件） | 图注入用量证明 1 跳不够（`graphReserve` 固定 220 字符，图注入目前是小配角） |
| B5 | `MemoryProvider` SPI 化 | 出现第二个记忆来源（如外部记忆服务接入决策） |
| C1 | 工具/技能权限三档映射 `approval_mode` | 随 A1/A2 启动时一起定 |
| C2 | 技能市场（复用 Agent 市场 Listing/Install/Review） | 技能层跑通且有跨租户共享需求 |
| C3 | 会话分叉（DSH seed 语义） | 砍——个人编程实验场景，企业客服用不上 |

## 5. 执行顺序

```
现在就做：A4（独立） ┃ B3（独立） ┃ C4（独立）   三项互不依赖，可并行
条件触发：A1+A2（满足触发条件）→ 随后 A3 + C1 → C2
产品决策：A5（出现对外开放需求时）
```

首批三项均为低-中复杂度、互不依赖，一个迭代内可并行交付。

## 6. 风险与取舍

- **ngram 全文索引**在 MySQL 上对短文本（记忆 fact）效果待验证，必要时像 RAG 域一样先治词表；不上 ES（新增中间件违背零依赖决策，除非评估通过且收益明确）。
- **MCP 连接池**与 AgentScope `McpClientBuilder` 生命周期耦合，须确认 wrapper 线程安全后再缓存，否则退化为会话级缓存。
- **不做可执行插件**意味着无法复用 DSH npm 生态，扩展速度慢于 DSH——多租户安全的必要代价，靠 A5 的 MCP 双向接入补位（条件触发）。
- **条件项有被遗忘的风险**：A1/A2 的触发条件（工具数 > 20~30、共享手册需求）需落到运营监控口径，建议随首批交付时在 Agent 管理页工具数指标上加观察项。

## 7. 明确不做

- 不引入 Mem0/Hindsight 等外部记忆服务（六层自研已覆盖，B5 留缝即可）。
- 不上 Cordis 类插件框架（Java 生态无对等物，SPI 足够）。
- 不做工作流=Skill 的封装（工作流保持节点引擎，技能引用工作流后置到 C2 之后）。
- 不做会话分叉（C3 砍掉，理由见 4.3）。
- PG 方言不在本方案范围（维持 docs/06 暂缓结论）。

## 附：修订记录

- 2026-11-15 v1：初稿，首批为 A3→A1→A2 + A4 + B1。
- 2026-11-15 v2：按定位适配评审收窄——DSH（单用户本地工作台）与 ForgeAgent（多租户企业平台）场景错配项降级；首批收窄为 A4+B3+C4；Skill 体系改条件项；B1/B2/B4/B5/C3 等需求。备份：`temp/08-记忆与Skill集成增强方案.bak_20261115`。
