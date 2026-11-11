# 智造 ForgeAgent 前端

前端基于 Vue 3 + TypeScript + Element Plus + Vite + Pinia 构建的管理端，采用卡片式设计语言与深浅色双主题，Agent 平台页面位于 `src/views/agent/`，智能客服域页面位于 `src/views/customerservice/`。技术栈：Vue 3.5 / Vite 8 / Pinia / vue-router / UnoCSS / ECharts / markdown-it，包管理器 pnpm 12。

## 目录说明

```
frontend/src
├── api/agent/              # Agent 平台各模块 API 封装与类型定义（session / workflow / model / tool / datasource / workspace 等）
├── views/agent/            # Agent 平台页面（工作空间 / 模型 / 工具 / 知识库 / 编排 / 对话 / 市场 / 治理等）
├── api/customerservice/    # 智能客服域 API（C1 工作台 / C2 运营 / C4 合规 / C5 呼叫中心 / A1~A5 坐席辅助，含完整 TS 类型）
├── views/customerservice/  # 智能客服域页面（坐席工作台 / 工单详情 / 会话面板 / 渠道接入 / 满意度 / 质检 / FAQ / 运营总览 / 合规 / 通话记录 / 意图管理 / 辅助看板）
├── api/system/             # 系统管理 API
├── views/system/           # 系统管理页面
├── layout/                 # 布局与导航（侧栏 / 顶栏 / 标签页）
├── components/             # 公共组件
├── router/ store/          # 路由与 Pinia 状态（user / permission / settings）
├── utils/forge.ts          # 通用工具函数
└── utils/governance.ts     # SSE 游标、变量契约等治理工具纯函数
```

交互约定：消息气泡只承载内容，操作工具栏常驻气泡外；全局样式令牌定义在 `assets/styles/tokens/`，深浅色主题通过 CSS 变量切换。每个客服域页面的 `<script setup>` 顶部有职责 / 数据流 / 扩展点注释块，新增页面照此维护。

## 开发与构建

```bash
pnpm install --registry=https://registry.npmmirror.com
pnpm dev          # 开发模式 http://localhost:8100，/dev-api 代理到后端 8080
pnpm build        # 生产构建（vite build --mode production）
pnpm preview      # 预览构建产物
pnpm lint         # oxlint src
pnpm test:unit    # vitest run（当前 15 用例）
pnpm typecheck    # vue-tsc --noEmit（仍有历史类型欠账，暂未接入构建）
```

开发代理 `/dev-api` 指向 `http://localhost:8080`，可在 `vite.config.ts` 调整。环境变量在 `.env.development` / `.env.production`：`VITE_APP_BASE_API`（dev `/dev-api`，prod `/prod-api`）、`VITE_APP_PORT`、`VITE_APP_CLIENT_ID`、`VITE_APP_RSA_*`（前后端加解密密钥对，上线前须与后端成对轮换）。

## 测试

```bash
pnpm test:unit
# 只跑指定文件
npx vitest run src/api/agent/session/__tests__/sse-events.test.ts src/utils/__tests__/governance.test.ts
```

当前 15 用例：`sse-events.test.ts`（10 用例，SSE 事件分发器 `dispatchStreamEvent` 的 run_id / delta / done / error / 工作流四分支 / 未知事件游标等契约）+ `governance.test.ts`（5 用例，治理工具纯函数）。SSE 分发逻辑已提取到 `src/api/agent/session/sse-events.ts`，回调接口 `StreamCallbacks` 与状态 `SseState` 独立可测，`index.ts` 负责连接、重放与断线续传。

## 关键约定

- **SSE 事件协议**：聊天主链 `run_id/meta/delta/usage/done/error`，团队接力 `team_agent_start/team_delta/team_agent_done`，工作流节点 `wf_start/node_start/node_done/node_error`。断线带上 `after=cursor` 重放，游标由 `nextSseCursor` 维护；未知事件静默忽略且游标前进，后端新增事件不会打断旧前端。
- **客服域 SSE**：访客侧逐字流式（`chat_chunk` 逐字 + `done` 终态 + 事件重放），复用全局 `RunEventBuffer` 游标机制；坐席弹屏与转写上屏走同一重放通道（`/chat-api/.../events?after=`），语音事件 `CALL_*`（ring/answer/transcript/hangup 等）由 V2 呼叫中心经同一信封下发。
- **坐席辅助（A1~A5）**：`ChatPanel.vue` 消费会话事件流——`cs_seat_suggest` 驱动推荐卡片（意图标签 + 采纳填入输入框 + 动作按钮），`call.transcript` 驱动转写上屏（中间结果覆盖同人未定稿行）。**事件端点的 `data` 是完整信封 JSON 字符串，业务字段在 `payload` 里**，统一经 `payloadOf` 解包；动作执行前必弹确认框（服务端不提供自动执行入口），执行成功后移除该推荐。`Intents.vue` 管意图标签（平台级只读），`AssistBoard.vue` 看采纳率（按推荐条数）、动作数、回流 FAQ 与低质推荐追溯。
- **客服域页面分工**：坐席工作台（工单抢单 / 回复 / 流转）→ 工单详情（七态跃迁 + 事件时间轴）→ 渠道接入（挂件 / 公众号 / 企微 / 语音）→ 运营三页（满意度 / 质检 / FAQ）→ 合规（审计 / 擦除）→ 通话记录（语音列表 + 录音回听 + 转写时间轴）→ 意图管理（规则打标配置）→ 辅助看板（采纳率 / 回流）。页面顶部注释块描述各自的数据流与扩展点。
- **录音播放**：前端只持后端返回的短期签名 URL（过期重新取回），不接触任何存储凭据；`recording-store: local-fs`（开发）与 `oss`（生产）对前端透明。
- **聊天页时间线**：团队接力轨迹与工作流节点轨迹都在气泡内实时点亮（工作流按 nodeId 去重防并行乱序）；时间线是流式实时态，历史消息只展示最终回复。
- **资源域双模式**：模型 / 工具 / 数据源三个页面共用 `views/agent/components/ResourceScopeFields.vue` 选择「平台共享（仅超管可见）」或「空间私有 + 归属空间」，归属空间下拉来自 `myWorkspaces()`；列表带「范围」标签。后端 `ResourceAccessGuard` 是最终权限防线，前端只负责呈现。
- **组件单根节点**：项目启用了 `check-transition` 插件，所有 `.vue` 模板必须有且仅有一个根节点（页面外层包裹 `<transition>`）。需要并列多个表单项时用容器元素包裹，可用 `display: contents` 保持布局。
- **权限指令**：按钮级权限用 `v-hasPermi="['agent:model:add']"`，与后端 `@SaCheckPermission` 对齐。

## 已知限制

- `pnpm typecheck` 仍有历史类型欠账，尚未接入构建门禁；
- 工作流节点时间线不随历史消息持久化，如需回放需从 Run 轨迹重建。

## 协议

MIT，详见仓库根目录 [LICENSE](../LICENSE) 与 [THIRD-PARTY-NOTICES](../THIRD-PARTY-NOTICES.md)。
