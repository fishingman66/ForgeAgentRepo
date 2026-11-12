# 智造 ForgeAgent 前端

Vue 3 + TypeScript + Element Plus + Vite 构建的管理端，采用卡片式设计语言与深浅色双主题。Agent 平台页面在 `src/views/agent/`，智能客服域页面在 `src/views/customerservice/`，系统管理在 `src/views/system/`。

| 项 | 版本 |
| --- | --- |
| 包名 / 版本 | `forgeagent-web` / 6.0.0 |
| 运行时 | Node >= 22.22.0、pnpm >= 12.0.0（锁 `pnpm@12.16.1`） |
| 核心 | Vue 3.5、Vite 8.3、TypeScript 6.0、vue-tsc 3.3、Element Plus 2.14、Pinia 4.1、vue-router 5.4 |
| 配套 | UnoCSS 66、ECharts 6.1、markdown-it 15、axios 1.21、vitest 5.1、oxlint / oxfmt |

## 目录说明

```
frontend/src
├── api/agent/              # Agent 平台 API 与类型定义
│   ├── knowledge/          # 知识库：文档治理 / 回收站 / 评测 / 质量问题 / VLM 配置 / 检索参数覆盖
│   ├── memory/             # 长期记忆：列表 / 语义检索 / 图谱 / 治理与遗忘
│   ├── workflow/           # 编排画布与试运行；workflow-endpoint/ 对外 API
│   ├── governance/ session/ semantic/ mcp/ trigger/ usage/ approval/ apikey/
│   └── agent/ model/ tool/ datasource/ provider/ workspace/ market/ audit/ feedback/ dashboard/
├── views/agent/            # Agent 平台页面（20 个业务目录）
│   ├── knowledge/          # 知识库主页面：治理 / 回收站 / 评测 / 分块编辑 / VLM 配置
│   ├── memory/             # 长期记忆管理页（语义检索 / 图谱 / 治理与遗忘三个视角）
│   ├── components/         # 复用组件（如 ResourceScopeFields.vue 资源域选择）
│   └── agent/ chat/ workflow/ market/ mcp/ trigger/ dashboard/ usage/ approval/ ...
├── api/customerservice/    # 智能客服域 API（C1 工作台 / C2 运营 / C4 合规 / C5 呼叫中心 / A1~A5 / P0~P3 / 图谱维护）
├── views/customerservice/  # 13 个页面：Workbench / TicketDetail / ChatPanel / Channels / CsatBoard /
│                           # QualityBoard / KnowledgeAnswers / Dashboard / Compliance / Calls /
│                           # Intents / AssistBoard / KnowledgeGraph
├── api/system/  views/system/  # 系统管理
├── layout/                 # 布局与导航（侧栏 / 顶栏 / 标签页）
├── components/             # 公共组件
├── router/ store/          # 路由与 Pinia 状态（user / permission / settings）
├── assets/styles/tokens/   # 全局样式令牌（深浅色主题走 CSS 变量）
├── utils/forge.ts          # 通用工具函数
└── utils/governance.ts     # SSE 游标、变量契约等治理工具纯函数
```

交互约定：消息气泡只承载内容，操作工具栏常驻气泡外。每个客服域页面的 `<script setup>` 顶部有职责 / 数据流 / 扩展点注释块，新增页面照此维护。

## 开发与构建

```bash
pnpm install --registry=https://registry.npmmirror.com
pnpm dev          # 开发模式 http://localhost:8100，/dev-api 代理到后端 8080
pnpm typecheck    # vue-tsc --noEmit（零错误；构建门禁）
pnpm build        # vue-tsc --noEmit && vite build --mode production
pnpm build:dev    # vue-tsc --noEmit && vite build --mode development
pnpm build:prod   # vue-tsc --noEmit && vite build --mode production
pnpm preview      # 预览构建产物
pnpm lint         # oxlint src
pnpm fmt          # oxfmt .
pnpm test:unit    # vitest run（15 用例）
```

**类型检查已接入构建门禁**：`build` / `build:dev` / `build:prod` 三条脚本都前置 `vue-tsc --noEmit &&`，类型不过构建直接终止。115 个存量类型错误已清零（历史遗留的 `.data?.data` 双层取值运行时 bug 也在此一并修复），新增代码不得再引入类型欠账。

开发代理 `/dev-api` 指向 `http://localhost:8080`，可在 `vite.config.ts` 调整。环境变量在 `.env.development` / `.env.production`：`VITE_APP_BASE_API`（dev `/dev-api`，prod `/prod-api`）、`VITE_APP_PORT`、`VITE_APP_CLIENT_ID`、`VITE_APP_RSA_*`（前后端加解密密钥对，上线前须与后端成对轮换）。

## 测试

```bash
pnpm test:unit
# 只跑指定文件
npx vitest run src/api/agent/session/__tests__/sse-events.test.ts src/utils/__tests__/governance.test.ts
```

当前 15 用例：`sse-events.test.ts`（10 用例，SSE 事件分发器 `dispatchStreamEvent` 的 run_id / delta / done / error / 工作流四分支 / 未知事件游标等契约）+ `governance.test.ts`（5 用例，治理工具纯函数）。SSE 分发逻辑已提取到 `src/api/agent/session/sse-events.ts`，回调接口 `StreamCallbacks` 与状态 `SseState` 独立可测，`index.ts` 负责连接、重放与断线续传。

## 知识库页面能力

`views/agent/knowledge/index.vue` 是 R1~R5 全部能力的前端入口：

- **知识库表单**：相似度阈值 / 精排阈值、零召回兜底、上传需审核、分块单位（char / token）与策略（结构感知 / 定长）、解析清洗规则、父子分块、**检索参数覆盖**（topK / 检索模式 / 两路权重 / 阈值 / 精排，留空跟随全局）、**图片描述**开关。
- **图片识别模型配置**（工具栏「图片识别模型」）：按工作空间配置平台模型或第三方 OpenAI 兼容模型（baseUrl / API Key / ModelName），支持连通性测试、`effectiveSource` 回显；**API Key 只回显掩码，编辑留空沿用旧密文**，界面从不接收明文回显。
- **文档治理**：可见性（公开 / 受限）+ 授权角色、发布态（草稿 / 待审 / 已发布 / 已驳回）、生效与过期时间窗、审核按钮；**回收站**切换与恢复，删除是软删。
- **检索评测**：用例管理、运行与历史、语料指纹对比、双跑门禁结论；逐用例明细含首中排名 / 证据覆盖 / 相关度 / 降级；零命中查询可一键生成评测用例，质量问题 3 态闭环。
- **分块编辑**：分块列表、启停、内容修改、父子定位。

## 长期记忆页面

`views/agent/memory/` 提供三个视角：记忆列表（分页 + 语义检索）、记忆图谱（实体与关系边、存量补建）、治理与遗忘（注入预览 `GET /agent/memory/preview`、用户级遗忘 `confirm=FORGET`、手动治理任务）。

## 关键约定

- **响应已解包一层**：`request` 拦截器把 `AxiosPromise<T>` 解包成 `{code, msg, data}`，业务数据在 `res.data`。写 `res.data?.data` 是历史 bug（曾导致客服 12 个页面指标恒为空），**新增页面不要照搬 axios 原生双层写法**。
- **模板状态一律用扁平 `ref`**：Volar 对模板中的 `v-model` / 内联赋值会生成 `<binding>.value.<field>`，`reactive` 对象没有 `.value` 会报 TS2339；嵌套 `reactive` + `v-model` 还会误插 `.value` 并级联污染整个模板（几十个假错）。`ref` 在模板自动解包、script 内写 `.value`。新弹窗放在模板末尾可进一步规避。
- **el-table slot 的 `row` 固定是 `DefaultRow`**：传给具体 VO 的函数要 `(row as XxxVO)` 断言，别用 `as any`。
- **组件单根节点**：项目启用 `check-transition` 插件，所有 `.vue` 模板必须有且仅有一个根节点（页面外层包裹 `<transition>`）。需要并列多个表单项时用容器元素包裹，可用 `display: contents` 保持布局。
- **图标与 import**：菜单图标必须在 `frontend/src/assets/icons/svg/` 存在（常见 `TS2724` 是图标名写错，如 `DocumentSave` 不存在）；import 不要带 `.ts` 后缀（会报 `TS5097`）。
- **SSE 事件协议**：聊天主链 `run_id/meta/delta/usage/done/error`，团队接力 `team_agent_start/team_delta/team_agent_done`，工作流节点 `wf_start/node_start/node_done/node_error`。断线带上 `after=cursor` 重放，游标由 `nextSseCursor` 维护；未知事件静默忽略且游标前进，后端新增事件不会打断旧前端。
- **客服域 SSE**：访客侧逐字流式（`chat_chunk` 逐字 + `done` 终态 + 事件重放），复用全局 `RunEventBuffer` 游标机制；坐席弹屏与转写上屏走同一重放通道（`/chat-api/.../events?after=`），语音事件 `CALL_*`（ring/answer/transcript/hangup 等）由呼叫中心经同一信封下发。
- **坐席辅助（A1~A5）**：`ChatPanel.vue` 消费会话事件流——`cs_seat_suggest` 驱动推荐卡片（意图标签 + 采纳填入输入框 + 动作按钮），`call.transcript` 驱动转写上屏（中间结果覆盖同人未定稿行）。**事件端点的 `data` 是完整信封 JSON 字符串，业务字段在 `payload` 里**，统一经 `payloadOf` 解包；动作执行前必弹确认框（服务端不提供自动执行入口），执行成功后移除该推荐。`Intents.vue` 管意图标签（平台级只读），`AssistBoard.vue` 看采纳率（按推荐条数）、动作数、回流 FAQ 与低质推荐追溯。
- **录音播放**：前端只持后端返回的短期签名 URL（过期重新取回），不接触任何存储凭据；`recording-store: local-fs`（开发）与 `oss`（生产）对前端透明。
- **聊天页时间线**：团队接力轨迹与工作流节点轨迹都在气泡内实时点亮（工作流按 nodeId 去重防并行乱序）；时间线是流式实时态，历史消息只展示最终回复。
- **资源域双模式**：模型 / 工具 / 数据源三个页面共用 `views/agent/components/ResourceScopeFields.vue` 选择「平台共享（仅超管可见）」或「空间私有 + 归属空间」，归属空间下拉来自 `myWorkspaces()`；列表带「范围」标签。后端 `ResourceAccessGuard` 是最终权限防线，前端只负责呈现。
- **权限指令**：按钮级权限用 `v-hasPermi="['agent:model:add']"`，与后端 `@SaCheckPermission` 对齐。

## 已知限制

- 工作流节点时间线不随历史消息持久化，如需回放需从 Run 轨迹重建；
- 前端不持任何模型 / 存储凭据，第三方图片识别模型的 API Key 只能写入不能读回（服务端仅返回掩码）；
- 单测目前集中在 SSE 分发与治理纯函数，页面级交互依赖 `temp/` 下的 Python 端到端脚本覆盖。

## 协议

MIT，详见仓库根目录 [LICENSE](../LICENSE) 与 [THIRD-PARTY-NOTICES](../THIRD-PARTY-NOTICES.md)。
