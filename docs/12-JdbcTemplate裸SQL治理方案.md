# JdbcTemplate 裸 SQL 治理方案（待确认）

> 状态：**已确认（2026-11-19）——方向 B（全量实体 + Mapper）/ 分批次推进 / 跨域接口放现有 callcenter-common**。调研日期 2026-11-19。
> 这是架构优化提案，不是缺陷修复——当前代码能跑、单测全绿，动它有成本，先讲清「为什么值得动」和「动到什么程度」。

---

## 一、现状盘点（实测，不靠印象）

| 维度 | 数据 |
|---|---|
| 使用范围 | 12 个生产类、106 处 `jdbc.*` 调用（另有 `forge-gen` 的 `MyBatisDataSourceMonitor` 5 处属基础设施监控，与业务 SQL 无关） |
| 模块分布 | forge-agent 10 类 / forge-customer-service 1 类（`CsEvalFeedbackService`）/ forge-gen 1 类 |
| 调用密度 Top | `EvaluationService` 25、`CsEvalFeedbackService` 15、`ReleaseGateService` 14、`PromptTemplateService` 14、`AgentVersionService` 13、`RunTraceService` 9 |
| 涉及表 | 20+ 张，几乎全是治理域：`agent_eval_case/run/dataset/result/baseline`、`agent_prompt_template/version`、`agent_version`、`agent_skill_version`、`release_gate_waiver`、`agent_version` 等 |
| **关键事实** | 这些表**全部没有对应实体类和 Mapper**。不是「有 Mapper 不用」，是治理功能落地时为快，直接建表 + JdbcTemplate 读写。项目里有 130+ 张表的标准 Mapper 体系，唯独治理域这一批裸奔 |
| SQL 形态 | 无动态拼接、无 `WHERE 1=1`、无字符串 + 条件，全是静态文本块（Java 15 text block）+ 占位符参数，**SQL 注入风险为零** |
| 复杂度 | 3~4 处多表 JOIN（`agent_skill_install JOIN agent_skill JOIN agent_skill_version` 等），其余是单表 CRUD / 聚合 COUNT / `INSERT...SELECT` |

### 为什么会变成这样（不是bug，是路径依赖）

治理域功能（评测、发布门禁、版本快照、Prompt 模板）是后期按批次增量加的，每次都赶交付节点。用 JdbcTemplate 的理由当时成立：

1. **跨域表直连**：`CsEvalFeedbackService`（客服模块）要写 `agent_eval_case`（agent 域表），引 agent 的 Mapper 会形成 `customer-service → agent → callcenter → customer-service` 的**反向依赖环**，与现有 `callcenter-common` 零依赖契约层的纪律冲突；
2. **表结构不稳定**：治理表反复加列（评测回流刚加过 `source/review_status/config_hash`），Map 直读直写不用同步改实体；
3. **返回形态就是 Map**：大量方法返回 `Map<String,Object>` 给前端/评测引擎，强类型化收益不明显。

问题在于：**当时成立不等于现在仍成立**。批次 A/B/C/D 落地后治理域已稳定（DDL 全折进 01-schema、表结构冻结），而 106 处裸 SQL 的维护成本开始显现——改一处列名要全局 grep、无编译期校验、无 IDE 补全、测试只能断言 SQL 字符串。

---

## 二、核心矛盾与方案选型

先明确一个判断：**这不是「要不要消灭 JdbcTemplate」的问题，而是「治理域要不要纳入与其余 130 张表一致的数据访问纪律」的问题**。所以我给四个方案，从保守到激进：

### 方案 A：保持现状 + 加约束（最低成本）

**做法**：不迁移代码，但立三条硬规矩——① 所有裸 SQL 集中到各模块的 `dao/raw` 包，服务层不许出现 `jdbc.*`；② 每张治理表建一个常量类存表名/列名（`AgentEvalCaseColumns`），消除字符串散落；③ 加一个 CI 检查：新增裸 SQL 必须有注释说明「为何不用 Mapper」。

- **得**：零迁移风险，1 天完成；列名集中后改列不再全局 grep；跨域直连现状不动。
- **失**：106 处 SQL 仍在，无编译期类型校验，JOIN 查询仍靠手写；本质上只是「收拾整齐的裸奔」。
- **适用**：如果维护成本主要来自「列名散落、改列易漏」，这剂药对症。

### 方案 B：MyBatis-Plus 实体 + Mapper 全量迁移（最彻底）

**做法**：为 20+ 张治理表补实体（照 `AgentKbEvalCase` 样板）+ `BaseMapperPlus` Mapper，把 JdbcTemplate 调用换成 MP 的 `selectById/insert/update` 和 `@Select` 注解（JOIN/聚合用 `@Select`，静态 SQL 天然适配）。跨域场景（客服写 agent 表）用**接口下沉**：在 `callcenter-common` 或新建 `forge-platform-contract` 定义 `EvalCaseWriter` 接口，agent 模块实现，客服模块只依赖接口——彻底解开依赖环。

- **得**：与项目其余 130 张表统一；编译期类型校验；列变更跟着实体走；分页/条件构造免费获得。
- **失**：20+ 实体 + Mapper + 100+ 处调用改造，**工作量约 5~8 人天**；跨域接口下沉要解决实例化与事务边界，是新复杂度；风险最高——治理域单测大量用纯 Mockito 打桩 `jdbc.queryForList`，全量迁移意味着测试也要重写（`AgentVersionServiceTest`/`EvaluationServiceTest` 等）。
- **适用**：如果目标是「架构纯粹、长期一致」，这是对的方向，但成本要认。

### 方案 C：中间层 DAO 抽象（推荐 ⭐）

**做法**：不动映射方式，但把 JdbcTemplate 调用收拢到**每张治理表一个 XxxDao 类**（手写薄封装），服务层只调 DAO 方法，签名强类型：

```java
// 治理域专属 DAO（放 forge-agent 模块 domain/dao）
public interface AgentEvalCaseDao {
    int insertPending(String source, String sourceRefId, String question, String reference, ...);
    List<EvalCaseRow> listPending(Long workspaceId, Long agentId);  // record 强类型返回
    boolean existsBySourceRef(String source, String sourceRefId);
    int markReviewed(Long id, String status, Long reviewerId);
}
// 实现类内部才用 JdbcTemplate + 集中列名常量；用 RowMapper 映射到 record
```

关键点：
- **签名强类型**：返回 `record` 而非 `Map<String,Object>`，调用方不再 get("xxx") 猜列名；
- **保留 JdbcTemplate**：JOIN/聚合/INSERT...SELECT 用原生 SQL 完全合理，不强行套 MP；
- **跨域问题照旧用接口**：`CsEvalFeedbackService` 改为依赖 `EvalCaseWriter` 接口（agent 侧实现），DAO 封装与接口下沉一次做完；
- **渐进迁移**：一次一张表，每次只动一个服务 + 它的单测，随时可停。

- **得**：解决核心痛点（散落的字符串列名、Map 弱类型、服务层混杂 SQL），又不引入全量实体/Mapper 的成本；每次迁移单元小、可验证；服务层测试改打 DAO mock 更简单。
- **失**：DAO 手写（20+ 类、约 2~3 人天分步完成）；仍有 SQL 字符串（但集中在 DAO 一个文件里）；与项目 MP 体系并存，是「两种风格」而非统一。
- **适用**：如果目标是「投入产出比最高、风险可控、解决 80% 痛点」，这剂药最对症。

### 方案 D：只迁跨域，其余不动（最小闭环）

**做法**：唯一有架构风险的是跨域直连（客服写 agent 表）。只把这一处（`CsEvalFeedbackService` 15 处调用）改成接口下沉，其余 90 处保持现状。

- **得**：1 天完成，解掉唯一的结构性依赖隐患。
- **失**：92 处裸 SQL 的维护痛点原样保留。
- **适用**：如果主要担心的是「模块依赖方向」而非「日常维护成本」。

---

## 三、我的推荐（以架构师视角，按优先级）

**推荐组合：C 为主 + D 的跨域接口下沉纳入 C 的第一批**

理由：

1. **区分两类问题**：跨域依赖环是「结构性风险」（违反既定纪律、会随模块增加而恶化），必须解——这部分取 D；裸 SQL 维护成本是「工程效率问题」，用 C 的 DAO 封装解决，不必全量换 MP。
2. **不追求形式统一**：这些治理表 90% 的 SQL 是 JOIN + 聚合 + 版本快照，MP 的强项是单表 CRUD，硬套 MP 反而会让 `@Select` 注解里塞满 SQL——本质还是裸 SQL，只是换了地方。C 方案承认「这类查询用原生 SQL 合理」，把精力放在封装质量上。
3. **可逆性**：DAO 类是薄封装，将来若真要换 MyBatis-Plus，只需替换 DAO 实现，服务层零改动。B 方案一旦全量迁移，发现不合适再退回来成本极高。

**建议的推进顺序（分批、每批可独立验收）**：

| 批次 | 内容 | 涉及类 | 预估 |
|---|---|---|---|
| 第 1 批 | 跨域解环：`EvalCaseWriter` 接口 + agent 侧实现，客服侧改依赖 | `CsEvalFeedbackService` | 0.5 天 |
| 第 2 批 | 评测域 DAO：`AgentEvalCaseDao` / `EvalRunDao` / `EvalDatasetDao` | `EvaluationService` | 1 天 |
| 第 3 批 | 版本/模板/门禁 DAO | `AgentVersionService` / `PromptTemplateService` / `ReleaseGateService` / `AgentSkillVersionService` | 1.5 天 |
| 第 4 批 | 追踪/护栏 DAO + 收尾 | `RunTraceService` / `CsToolGuardService` 等 | 0.5 天 |

每批完成后跑全仓 surefire（当前基线 **828**）+ 行尾复核，与 A~D 四批次的推进纪律一致。

---

## 四、明确不做（避免方案膨胀）

- **不引 JPA / QueryDSL / jOOQ**：项目主力已是 MyBatis-Plus，再引第三套 ORM 只会增加认知负担；
- **不为 20 张表全建 CRUD 实体**：治理表多数只有 2~5 个访问入口，建全套实体的收益覆盖不了成本；
- **不改 `MyBatisDataSourceMonitor`**：那是数据源健康监控，与业务 SQL 治理无关；
- **不动 01-schema**：本方案只改 Java 访问层，不涉及 DDL。

---

## 五、最终决策（2026-11-19 用户确认）

1. **方向**：**B —— 全量实体 + Mapper 迁移**（非原推荐的 C，用户选择架构纯粹性）。
2. **推进节奏**：**分批次推进**，每批独立验收（全仓 surefire 基线 828 + 行尾复核）。
3. **跨域解环落点**：**现有 `callcenter-common`**（零 Spring 依赖契约层，加接口不破坏纪律，少一个模块）。

### 按方向 B 重排的批次计划（替换第三节 C 方案的 4 批表）

| 批次 | 内容 | 涉及服务 | JdbcTemplate 调用数 |
|---|---|---|---|
| 第 1 批 | 评测域 5 表（`agent_eval_case/run/dataset/case_result/baseline`）实体 + Mapper + 跨域 `EvalCaseWriter` 接口解环 | `EvaluationService`(25) / `CsEvalFeedbackService`(15) | 40 |
| 第 2 批 | 版本/模板/门禁域 5 表（`agent_version/prompt_template/prompt_version/skill_version/gate_waiver`） | `AgentVersionService`(13) / `PromptTemplateService`(14) / `ReleaseGateService`(14) / `AgentSkillVersionService`(4) | 45 |
| 第 3 批 | 追踪/护栏域 | `RunTraceService`(9) / `CsToolGuardService`(8) / `WorkflowSchemaGuard`(1) / `CsTraceExtenderImpl`(1) / `CsRunOwnershipImpl`(1) | 20 |

**迁移单元**（每表）：补 `@TableName` 实体（照 `AgentKbEvalCase` 样板）→ `BaseMapperPlus` Mapper → 服务层 `jdbc.*` 换 MP 方法，JOIN/聚合用 `@Select` 注解承载原生 SQL → 单测 mock 从 `JdbcTemplate` 换 Mapper → 验收。

---

## 六、Bo/Vo 领域对象补齐（2026-11-19 第二次确认）

用户确认：**方向 A——完整规范化**，对第 1、2 批（评测域 5 表 + 版本/模板/门禁域 8 表）一并补齐 Bo/Vo，
controller 从 `Map<String,Object>` 改强类型，前端同步驼峰契约。

### 交付内容

- **13 个 Vo**（`domain/vo/`，挂 `@AutoMapper(target=Entity.class)`）：`AgentVersionVo`(含 snapshot) /
  `VersionDiffVo`(嵌套 FieldChange) / `ReleaseGateExecutionVo` / `ReleaseGateWaiverVo` /
  `AgentPromptTemplateVo` / `AgentPromptVersionVo` / `PromptPreviewVo` / `AgentEvalDatasetVo`(含 caseCount) /
  `AgentEvalCaseVo` / `AgentEvalRunVo`(judgeEnabled 统一 Boolean) / `EvalRunOutcomeVo` /
  `AgentEvalCaseResultVo` / `EvalRunDetailVo`(run+results 组合)。
- **9 个 Bo**（`domain/bo/`，`@NotNull/@NotBlank` + `@Validated`）：`GovernanceRollbackBo` /
  `GateWaiverRequestBo` / `PromptTemplateSaveBo` / `PromptVersionPublishBo` / `PromptStableBo` /
  `PromptPreviewBo` / `EvalDatasetCreateBo` / `EvalCasesAddBo`(嵌套 CaseItem) / `EvalRunStartBo`(含 of 工厂)。
- **Mapper `@Select` 返回类型改 Vo**（9 处，SQL 不动，MyBatis 驼峰映射直连）；内部消费查询
  （`selectApproved`/`checkOwnership`/`latestSuccess`/`currentBindings`/`skillRefsOf` 等）保留 Map 不对外。
- **服务层对外签名全部强类型**；controller 29 处 `Map` 入参换 Bo、`R<Object>` 换具体 Vo 泛型。
- **前端 4 文件 55 处蛇形→驼峰**（`governance/index.ts` + 三个 Drawer），vue-tsc 门禁通过。

### 顺带修复的既有缺陷（迁移期/历史）

1. `compileForAgent`/`preview` 读 `version.get("variable_schema")`（下划线键）而 `getVersion` 已是
   驼峰 Map——恒取 null，Vo 化改 getter 修复（影响 LOCKED/FOLLOW_STABLE 编译与预览契约）；
2. 前端 `getPromptTemplate`/`getPromptVersion` 读 `d.variable_schema` 而后端出驼峰——恒空，本次对齐；
3. `versionDetail` 的 `remove("snapshot_json")` 死代码清理（Vo 不再携带原始 snapshotJson 串）。

### 兼容性说明

- 入参：前端请求体本来全发驼峰，Bo 零迁移成本；
- 出参：列表/明细 JSON 字段名下划线→驼峰，前端同步修改（本批一并交付）；
- `judgeEnabled` 双语义（run 出 boolean / runDetail 出 "1"/"0"）统一为 Boolean，前端未读该字段无感。

### 范围边界

- 第 3 批（追踪/护栏域）迁移时按同一规范补 Bo/Vo；
- `GateResult`（服务内 record）已是强类型，保持不动；
- snapshot 内部结构（skillRefs List<Map>）参与 config_hash 计算口径，**不改结构**。

### 验收

- 后端 test-cs-batcha：agent 218 / cs 120 / callcenter 196 / callcenter-common 19，0 失败；
- 前端 `pnpm typecheck` EXIT=0；
- 行尾复核：本次触碰的 105 个文件全部 LF（5 个 CRLF 文件为历史遗留未触碰）。

---

## 七、第 3 批交付：追踪/护栏域（2026-11-19，全治理收官）

### 迁移清单（JdbcTemplate 全仓清零）

| 类 | 原调用数 | 迁移后 |
|---|---|---|
| `RunTraceService` | 9 | `AgentRunMapper`+`AgentRunStepMapper`;INSERT IGNORE→ASSIGN_ID 主键+`DuplicateKeyException` 捕获;首 Token 仅首写→`isNull(firstTokenLatencyMs)` 条件更新;detail 返回 `RunTraceDetailVo`(run+steps) |
| `CsToolGuardService` | 8 | `AgentCsActionLogMapper`+`AgentCsAuditLogMapper`;幂等占位 selectOne→插入实体 MP 回填 id(替代原"插完再 SELECT id"二次查询);审计改实体 insert |
| `WorkflowSchemaGuard` | 1 | `SchemaIntrospectMapper`(information_schema 元数据无业务表归属,专责独立接口,不挂实体) |
| `CsTraceExtenderImpl` | 1 | `AgentRunMapper` LambdaUpdateWrapper |
| `CsRunOwnershipImpl` | 1 | `AgentRunMapper.selectVisitorChannel`(@Select 返回 AgentRunVo,替代 Map 手工 asLong) |

### 新增领域对象

- 实体 4:`AgentRunStep`/`AgentCsActionLog`/`AgentCsAuditLog`(AgentRun 第 2 批已有)。
- Mapper 4:`AgentRunStepMapper`(nextStepNo/listSteps)/`AgentCsActionLogMapper`/`AgentCsAuditLogMapper`/`SchemaIntrospectMapper`;`AgentRunMapper` 扩 selectOwner/selectVisitorChannel。
- Vo 4:`AgentRunVo`/`AgentRunStepVo`/`RunTraceDetailVo`(+第 2 批已有的 `AgentEvalCaseResultVo` 复用于 steps 类型化)。

### 前端同步

`RunTraceDrawer.vue`(18 处)+`api/agent/session/index.ts`(8 处)蛇形→驼峰,`pnpm typecheck` EXIT=0。

### 测试口径变化

- `CsToolGuardServiceTest` 重写为双 Mapper mock:审计断言从「SQL contains+参数数组下标」改为「实体 captor+getter」;
  幂等占位依赖 MP insert 回填 id→单测桩里 doAnswer setId(呼叫中心同口径)。
- 语义保持:FAILED 重试=reserve 置 RUNNING+finish 收尾共 2 次 update(times(2));`ToolResultBlock.text()`
  默认 state=RUNNING,审计按实际 state 断言(旧测试 assert SUCCESS 依赖 SQL contains 误标)。
- MP lambda cache 预热(@BeforeAll initTableInfo AgentCsActionLog)再次踩中,已按 MEMORY 常规处理。

### 验收

agent 218 / cs 120 / callcenter 196 / callcenter-common 19,0 失败;行尾 28 文件全 LF。
**三批全部收官:全仓 JdbcTemplate 业务裸 SQL 清零**(保留 MyBatisDataSourceMonitor 数据源监控,与业务无关)。


---

## 八、Bo/Vo 全仓排查与批次 1 交付：客服域（2026-11-19 晚）

### 全仓排查结论

三批 JdbcTemplate 治理收官后全仓排查 Bo/Vo 覆盖:分四类处置——
1. **对外 Map 透传(需补)**:客服 13 + rag 5 + callcenter 2 + agent 3 + memory 1(agent 实测 3,原估 2,第九节修正);
2. **实体直出接口(需补 Vo)**:客服 29 / callcenter 8 / rag 4(JSON 是 MP 驼峰,前端零改动,收益=过滤内部字段+契约解耦);
3. **缺 Vo 无对外接口(不补)**:AgentCsActionLog/ReleaseGatePolicy 等纯内部实体,建 Vo 是死资产;
4. **刻意保留(不动)**:治理域 snapshot(锁 config_hash 口径)、D2 回流 CsEvalCaseRow(注释标明直出下划线)、
   gen/workflow(RuoYi/warm-flow 集成域)、sse-events(蛇形是事件名非字段)。

### 批次 1 交付(客服域,本轮)

- **Vo 34 个**(`domain/vo/`):聚合 Vo 16(CsSendResultVo 承载 send 五形态可选字段 / CsAssistOverviewVo、
  CsCostOverviewVo 嵌套 Total 对齐前端 TS 类型 / CsHandoffViewVo / CsTicketDetailVo / CsSummaryVo …)
  + 实体 Vo 17(按实体字段程序化生成,@AutoMapper 挂实体,CsChannelVo 排除 channelSecret 不出网)。
- **Bo 14 个**(`domain/bo/`,对齐 AgentDatasourceBo 惯例:`@AutoMapper(target=X, reverseConvertGenerate=false)`
  + AddGroup/EditGroup 分组校验 + @Size 边界):CsIntentSaveBo / CsQualityRuleSaveBo / CsSensitiveWordSaveBo /
  CsAgentRouteSaveBo / CsKnowledgeAnswerSaveBo / CsChannelSaveBo / CsVisitorSendBo / CsCsatSubmitBo /
  CsReplyBo / CsNoteBo / CsTransferBo / CsHandoffBo / CsVisitorCreateSessionBo / CsEvalReviewBo。
- **9 个 controller 全强类型**:Map 入参→Bo(@Validated),Map/实体出参→Vo;5 个 Map 组装 service
  (CsVisitorService put→setter 全量转换)与 11 个实体直出 service(MapstructUtils.convert 包一层)。
- **跨模块连带**:callcenter CallBusinessBinder、agent-core OpenAiCompatController、CsChannelDispatchService
  消费 send/createSession 的 Map 改 Vo getter。

### 踩坑记录

1. **MapstructUtils 静态依赖 Spring 容器**(`SpringUtils.getBean` 在 `<clinit>`),纯 Mockito 单测连
   mockStatic 都救不了(类加载即炸)——解法:service 抽 `protected toVoList()` hook,测试 spy 覆写。
   命名的 protected hook 仅 CsMetricService 一处(其余 convert 都在 controller/浅层,不在测试路径)。
2. 实体 Vo 逐个手写字段必漏(CsMetricDailyVo 漏 containmentRate 等真实指标字段)——改为
   **按实体 private 字段程序化生成**,一次到位。
3. `R<Void>` 方法体里 `return R.ok(service.xxx())` 编译不过(T 推断失败)——改为先调后 `R.ok()`。

### 验收

agent 218 / cs 120 / callcenter 196 / common 19 全绿(首轮 IVR 端到端 20s 超时为偶发,复跑即绿);
行尾 122 文件全 LF。

### 批次 2 待办

rag 5 Map 接口 + callcenter 2 Map 接口 + agent 3 + memory 1 尾巴;实体直出 rag 4 / callcenter 8 补 Vo。

---

## 九、批次 2 交付:rag / callcenter / agent / memory 尾巴(2026-11-19 深夜)

### 实际范围(实测修正,比原预估多 1 处)

原估 10 个 Map 接口(rag 5 + callcenter 2 + agent 2 + memory 1),实测 **11 处**——
agent 侧原估 2 处,实测 3 处:多出 `AgentDatasourceController` 的 `/{id}/tables`(List<Map>)。
另两处分别是 `AgentDatasourceController` 的 `/test`(Map.of 组装)与 `AgentWorkflowApiController`
的 `/workflow-api/{endpointCode}`(Map 组装)。三处一并处理。

| 模块 | Map 透传接口 | 实体直出 |
|---|---|---|
| rag | `AgentKbEvalController` run/compare/gate、`ExtSource` sync、`VlmConfig` test = **5** | AgentKbEvalCase / AgentKbEvalRun / AgentKbExtSource = **4** |
| callcenter | `CsCallController` detail/recording = **2** | CsCall / CsCallEvent / CsCallSegment / CsCallSeatState / CsCallMetricDaily / CsCallProfile / CsCallbackTask / CsCallChannelConfig = **8** |
| agent | datasource test、datasource tables、workflow-api invoke = **3** | — |
| memory | `runGovernance` = **1** | — |

### 新增领域对象

**Vo 25 个**

- rag(11):`AgentKbEvalCaseVo`、`AgentKbEvalRunVo`、`AgentKbExtSourceVo`(实体 Vo,@AutoMapper 挂实体)、
  `KbEvalRunResultVo`(run + hitRate + metrics + deprecatedCases + caseResults)、`KbEvalCaseResultVo`、
  `KbEvalMetricsVo` / `KbEvalGroupMetricsVo`(把 `EvalMetricsCalculator` 的 record 摊成可序列化 Vo)、
  `KbEvalCompareVo`、`KbEvalGateVo`、`KbExtSourceSyncVo`、`KbVlmConfigTestVo`。
- callcenter(10):8 个实体 Vo(程序化生成,字段与实体一一对应)+ `CsCallDetailVo`(call/events/segments)、
  `CsCallRecordingVo`(url/ossKey/expireSeconds)。`CsCallChannelConfigVo` 刻意不含 `voiceConfigJson`
  与 `delFlag`(接入细节 + 内部标记),`callbackSecret` 只承载 `__SET__` 标记。
- agent(3):`AgentDatasourceTestVo`、`AgentDatasourceTableVo`、`AgentWorkflowApiInvokeVo`。
- memory(1):`MemoryGovernanceResultVo`。

**Bo 5 个**(对齐 `AgentDatasourceBo` 惯例)

`KbEvalCaseBo`(评测用例,替换 `addCase` 的实体入参)、`KbExtSourceBo`(外部源登记,替换 4 个散参)、
`CsCallChannelConfigBo`(渠道 upsert,替换实体入参)、`CsCallSeatBindingBo`(坐席绑定)、
`CsCallChannelStatusBo`(启停,替换 `Map<String,String>` body)。

### 关键决策

- **rag 的 record 不进对外契约**:`EvalMetricsCalculator.Metrics/GroupMetrics`、`GateJudgeCore.Verdict`
  是计算器内部类型,直接返回会把内部结构焊进 API;新增 Vo 摊平并逐字段映射(顺带保留 `metricsJson` 落库口径不变)。
- **`gateCheck` 返回类型变更连带内部调用**:`AgentKnowledgeController.checkRebuildGate()` 原来
  `verdict.get("state")`,随之改为 `verdict.getState()`,避免同一接口两套消费方式。
- **`listCases`/`listRuns` 语义拆分**:对外 `listCases()` 直接返回 Vo;评测运行内部走新增的私有
  `listCaseEntities()`,不为了复用 Vo 而丢掉 evidences/keywords 原始列。
- **`KbEvalController.addCase` 改 Bo 后仍保留 `AgentKbEvalCase` 实体**:Bo→实体转换放 controller 私有
  `toEntity()`,service 签名不动(`KbQualityIssueServiceImpl` 内部造实体的路径无需改动)。
- **`CsCallController` 抽 12 个 protected 转换方法**(toCallVo/toEventVo/toSegmentVo/toSeatStateVo/
  toMetricVo/toProfileVo/toCallbackVo 及其 List 版),单测可 spy 覆写,不依赖 MapStruct 生成代码。
- **`AgentWorkflowApiController` 是免登录对外契约**:字段名(runId/status/output/message/costMs)与顺序
  全部保留,只把 `Map` 换成 Vo,**外部调用方零感知**。

### 踩坑记录(增量)

4. **`CsVoiceOpsControllerTest` 用实体当入参**:改造后必须同步把测试改成 Bo,否则编译不过;
   顺带补 `ArgumentCaptor` 断言,验证 Bo 字段真的落到了实体("入参不许静默丢失")。
5. **`MemoryGovernanceServiceTest` 断言 `Map.get("ttlArchived")`**:`runOnce()` 返回 Vo 后改为
   `getTtlArchived()`;`containsKey("archivedMessages")` 那种"存在性"断言改成 `assertEquals(0L, ...)`。
6. **`ExtSource.create` 原本是 4 个散参**:改 `@Validated(AddGroup.class) KbExtSourceBo bo`(非
   `@RequestBody`,仍是 query 绑定),验收脚本 `r5b_verify.py` 的 `params=` 调用方式不受影响。

### 前端同步

`pnpm typecheck` 全绿,零改动。原因:实体 Vo 的 JSON 字段名与 MP 驼峰实体完全一致,前端 TS 类型
(`CsCall`/`CsCallEvent`/`CsCallSegment`/`CsCallProfile`/`CsCallbackTask`/`CsCallSeatState`/
`CsVoiceChannel`/`KbEvalCaseVO`)本就不含被过滤的内部字段(`voiceConfigJson`/`delFlag`)。

### 验收

- 全仓 `mvn test`:**829 tests / 0 failures / 0 errors**(跳过 2 个 Milvus 集成测试)。
  分模块:agent 218 / rag 204 / callcenter 196 / cs 120 / memory 51 / kg 20 / admin 1 / 契约 19。
- `pnpm typecheck` 通过。
- 行尾:本次 24 个改动源文件全 LF(运行时生成的 `target/generated-sources` 不在此列)。
- 验收脚本口径不变:`r3_verify.py`(run/caseResults/state/intersectionCases)、`r5b_verify.py`
  (sourceId/imported/skipped/failed)、`r5d_verify.py`(ok/message)、`m4_e2e.py`(ttlArchived)
  消费的字段名全部保留。

### 阶段结论

Bo/Vo 全仓排查的四类处置**全部结案**:类别 1(对外 Map 透传)与类别 2(实体直出)零残留,
类别 3(死资产)与类别 4(刻意保留)按原判定不动。


---

## 十、批次 2 复盘与文档校正(2026-11-19 深夜)

### 本次校正的文档硬伤

1. 第九节标题下的 Vo 总数原写 23,但分项 rag 11 + callcenter 10 + agent 3 + memory 1 = **25**,已改正。
2. 第九节把 workflow-api 说成 `AgentDatasourceController` 的第 3 个 Map 接口,实际它在
   `AgentWorkflowApiController`;正确的第 3 处是 `AgentDatasourceController` 的 `/{id}/tables`。已改正。
3. 第八节与第九节的 callcenter 实体直出原写 7,实际交付 8 个实体 Vo(漏算 `CsCallChannelConfig`),
   两处均改为 8;agent Map 接口原估 2 改为实测 3。

### 代码复盘:确认无需回改的点

- **Vo 上 `@AutoMapper(target=X)` 不写 `reverseConvertGenerate=false`**:这是全仓 70+ 个既有 Vo 的统一
  惯例;`reverseConvertGenerate=false` 是 **Bo** 的模板约束(`AgentDatasourceBo`),不应套到 Vo 上。
- **`KbExtSourceBo` 用 query 绑定而非 `@RequestBody`**:create 原本就是 4 个散参,`r5b_verify.py`
  用 `params=` 调用;改 Bo 保持 query 绑定只是收口入参形状,不改协议,前端与脚本零改动。
- **`CsCallChannelStatusBo` 无 `@AutoMapper`/分组校验**:它是单字段命令对象(status + 路径 id),
  没有对应可映射实体,也没有新增/编辑两套校验;按模板硬套反而会生成错误映射。
- **Controller 手写 Bo→实体 / 实体→Vo**:`MapstructUtils` 的 `<clinit>` 调 `SpringUtils.getBean`,
  纯 Mockito 单测类加载即失败;手写 setter + `protected` 转换钩子是为可测性做的取舍,不改动。
- **`KbEvalServiceImpl` 内部仍用 `Map<String,Object>`**:`caseOutcomeRows`/`parseSnapshots` 是
  `case_results_json` 列的序列化与反序列化,不进入对外响应;对外已全部是 `KbEvalCaseResultVo`。
- **`CsVoiceCallbackController` 返回 `Map<String,String>`**:AICC 免登录回调的固定协议
  `{message, retcode}`,全局 `R<T>` 包装不适用,类注释已写明,属刻意保留。
- **`AgentGovernanceController.gatePreview` 返回 `ReleaseGateService.GateResult` record**:
  前端 `VersionDrawer.vue` 对 `gate.checks` 做 `v-for (value, key)` 动态遍历,强类型化会破坏契约,
  属治理域刻意保留,不在本批范围。
- **18 个 CRLF 源文件**:均为本批之前就存在(含原本即 CRLF 的 `AgentTool.java`),批次2 改动的
  24 个源文件已复核全 LF,不批量转码以免污染历史文件。

### 结论

复盘只发现文档数字/路径描述错误,未发现批次2 代码需要回改的逻辑或契约问题。质量门禁维持
全仓 829 tests / 0 failures 与 `pnpm typecheck` 全绿,验收脚本 r3/r5b/r5d/m4 字段口径不变。
