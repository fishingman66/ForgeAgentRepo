# 09 - 智能坐席对接 FreeSWITCH（FusionPBX）开发方案

> 状态：方案稿 v1.0（2026-11-17）。目标环境：FusionPBX 5.6（Ubuntu 24.04，前端 `https://<FusionPBX管理端地址>`），FreeSWITCH 随 FusionPBX 安装。
> 上游依据：`docs/07-呼叫中心增强方案.md`（v2.1）、`docs/04-语音呼叫中心.md`。本文只讲"真实对接怎么落地"，不重复上游设计取舍。
> 一句话结论：**项目的四层 SPI 已经为真实 FS 留好了插槽，缺的是 4 个真实实现 + 1 个生产会话入口 + FusionPBX 侧事件发射器。对接工作是"填空 + 接通"，不是重构。**

---

## 一、现状盘点（代码实证，非推测）

对接前必须认清哪些是现成的、哪些是空的，否则会重复造轮子或漏掉断点。

### 1.1 可直接复用、无需改动的资产

| 资产 | 位置 | 说明 |
|---|---|---|
| 网关契约 | `gateway/CsVoiceGateway.java` | 三方法 `supports/verify/parse/fetchRecording`，换实现不动业务 |
| 事件归一化表 | `gateway/FsEventNormalizer.java` | `FS_EVENT_TYPE_MAP`（10 条）+ `toContent()` 头映射，**Mock 与真实共用同一张表** |
| 事件编排 | `service/CallEventDispatcher.java` | 七阶段：渠道校验→时间窗→nonce→网关选择→验签→parse→逐事件分发，含死信 |
| 8 态状态机 | `domain/CsCallStatus.java` + `service/CallStateMachine.java` | 单调 rank，乱序/CDR 迟到不回退 |
| 幂等/CDR/录音合规/洞察/分流 | `service/`、`insight/`、`route/`、`ops/` | C4/C5/C8 已交付，与媒体来源无关 |
| 坐席辅助 | `route/DirectAnswerRouter.java`、`insight/InsightPipeline.java` | 无通道时留痕 `PENDING_NO_CHANNEL`，接上真通道即自动生效 |
| 零依赖契约层 | `forge-callcenter-common` | 不得引入 Spring/Jackson |

### 1.2 必须新增的空槽（当前完全不存在）

| 空槽 | 应实现接口 | 当前状态 | 批次 |
|---|---|---|---|
| 真实事件源 `FsGateway` | `CsVoiceGateway`，`supports("FS")` | **不存在**；`provider=FS` 在路由层被刻意拒绝（`CallEventDispatcherRoutingTest` 锁定） | P1 |
| ESL 接入入口 | 新组件（长连接订阅 :8021） | 不存在；现有入口是 HTTP `/chat-voice/{provider}/{code}` | P1 |
| `FsChannelControl` | `engine/ChannelControl` | 不存在；仅 `MockChannelControl`（内存轨迹） | P2 |
| 生产会话入口 | 调用 `IvrSessionDriver.drive(...)` 并注册真实 channel | **主代码无任何调用方**，注册表永远为空——这是最关键断点 | P2 |
| `FsMediaStreamProvider` | `engine/MediaStreamProvider`/`MediaStream` | 不存在；仅 Mock（内存注入） | P3 |
| 真实 ASR/TTS | `engine/AsrEngine`/`TtsEngine` | 不存在；`registry.asr("dashscope")` 直接抛异常 | P3 |
| 真实录音取回 | `CsVoiceGateway.fetchRecording` | Mock 版只认 `mock://`，其余抛异常 | P1 |
| 外呼 originate / ACD 契约扩展 | `CsVoiceGateway` 新增方法 | 不存在，且 `number-provider`/`trunk-provider` 是死配置（`CallcenterConfiguration` 硬编码 Noop） | P4 |

### 1.3 三个"看起来像 bug、其实是刻意设计"的点

1. `provider=FS` 被拒绝是**路由测试锁定的正确行为**，真实网关到位后才放开，不要改测试。
2. `LiveChannelRegistry` 是纯内存注册表，不需要"真实实现"，缺的是把真实 `ChannelControl` 实例**注册进来**。
3. 前端 `Calls.vue` 只做只读监控（11 项指标/记录/画像/回拨/就绪态），没有任何接听/挂断/状态上报端点；这与"坐席话机由 FusionPBX 承载"的边界一致，不是遗漏。

---

## 二、对接架构：四条链路

真实对接 = 把 Mock 的四条"内存链路"换成"网络链路"，业务编排层完全不动。

| 链路 | Mock 现状 | 真实实现 | 承载内容 |
|---|---|---|---|
| ① 事件面 | fixture 回放 HTTP POST | **ESL inbound** 订阅 :8021 → `FsGateway.parse` → `CallEventDispatcher` | 振铃/接听/挂机/DTMF/转人工/CDR |
| ② 控制面 | `MockChannelControl` 内存轨迹 | `FsChannelControl` = ESL `api`/`uuid_*` 命令 | answer/放音/打断/transfer/录音/hangup |
| ③ 媒体面 | `MockMediaStreamProvider` 注入静音 | `FsMediaStreamProvider` = `mod_audio_stream` WebSocket 双工 L16 PCM | 8k 音频进出，ASR/TTS/barge-in |
| ④ 坐席面 | fixture 注入 `seat_state` | FusionPBX `mod_callcenter` 队列 + 自带 WebRTC 话机 | workNo↔extension 绑定、ACD 转人工、就绪态 |

事件接入的**关键设计决策**：ESL listener 收到 `EslEvent` 后，转成 `FsEventNormalizer` 期望的 rawBody（`{"events":[{eslEventName,workNo,eventGenTimeMs,headers}]}`）再调 `gateway.parse`。这样归一化表零改动，Mock 与真实共用一套断言。验签从 HMAC 改为"ESL 连接密码 + 内网 ACL"，`CallIdempotencyService` 幂等照旧。

---

## 三、前置：FusionPBX 侧准备（P0，无 Java 代码）

这些不在 ForgeAgent 仓库内，但**不做完后面全部阻塞**。注意 `<FusionPBX管理端地址>` 是 Web 前端地址，不是对接端点。

### 3.1 必须向用户确认的网络事实（第一决策点）

- **ESL 8021 只监听内网**。公网裸奔等于把 calls/transfer/hangup 控制权交给全网（可 originate 任意号码）。两种合法拓扑：
  - A（推荐）：ForgeAgent 后端部署到**同一台云主机或同内网**，ESL 走 `127.0.0.1`/内网 IP。
  - B：后端留在本地，用 WireGuard/SSH 隧道打通 8021 + 音频 WS 端口，ESL 仍只绑内网。
  - 若当前只有公网 HTTPS 而无内网通道，**先解决通道，不写对接代码**。
- 开放 ESL 前先确认密码：配置文件 `autoload_configs/event_socket.conf.xml`，默认 `ClueCon` 必须改；FusionPBX 侧 `/etc/fusionpbx/config.conf` 的 `switch.event_socket.*` 保持同步。
- 用 `ss -lntup | grep 8021` 看监听范围，`fs_cli -H 127.0.0.1 -P 8021 -p '<密码>'` 验证连通。

### 3.2 FusionPBX 侧配置项

| 项 | 动作 | 备注 |
|---|---|---|
| 内部分机 | 建 2~3 个测试分机（如 021800001/021800002），用 Zoiper/MicroSIP 或 FusionPBX 自带 WebRTC 话机注册 | **无需 PSTN 资质**即可形成完整回路 |
| `mod_callcenter` | 建队列 `after_sales`（与 `agent_cc_c3.sql` 种子 `transfer.queue` 同名）、加坐席成员 | 承载转人工 |
| 拨号计划 | 建一个"呼入→IVR→转人工"的 demo 路由，指向 ForgeAgent | 首期只验证内部分机呼入 |
| 自定义事件发射 | 在 dialplan/Lua 中按 `FsEventNormalizer.FS_EVENT_TYPE_MAP` 发射 `forgeagent::*` 事件 | **关键**：标准事件（CHANNEL_*）订阅即有，但 asr_result/bot_utterance/transfer/record_ready/cdr 是自定义事件，不会自动出现 |
| 录音 | `uuid_record` 落盘路径规整，供 `fetchRecording` 取回 | 本地文件或 OSS，二选一 |
| `mod_audio_stream` | 源码编译并加载（社区版 1.10.x 不带） | **P3 高风险项**，见第九节 |

---

## 四、分阶段实施计划

严格按依赖顺序推进，每阶段独立可验收、可回滚。真实链路代码用 `@ConditionalOnProperty` 开关，与 Mock 并存，prod 校验器拦截 Mock。

### P1 — 事件面打通（C1-real，核心，无音频）

目标：内部分机呼入 → ForgeAgent 收到真实事件 → 8 态状态机正确流转 → CDR/录音落库。

1. `pom.xml` 加 ESL 依赖（坐标见第八节勘误），加配置 `agent.callcenter.freeswitch.esl.*`。
2. 新建 `FsGateway implements CsVoiceGateway`：`supports("FS")`；`verify` 走连接级认证（不再校验 HMAC 头）；`parse` 直接委托 `FsEventNormalizer.parse`；`fetchRecording` 支持本地文件路径 + OSS。
3. 新建 `FsEslEventListener`：ESL inbound 长连接、断线重连、`event plain CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_HANGUP_COMPLETE DTMF CUSTOM forgeagent::* callcenter::*`；把 `EslEvent` 转成 rawBody 喂 `dispatcher.dispatch(...)`（或抽一个 `dispatchFromEsl` 复用七阶段后半段）。
4. 放开路由：`FsGateway.supports("FS")` 后测试里的 `unroutableProvidersRejected` 断言需同步调整（把 FS 从"应拒绝"移到"应命中"）。
5. 扩展 `CallcenterProductionReadinessValidator`：prod 下 ESL 密码缺失/默认值 → 拒绝启动。

验收：真机打一通内部分机呼叫，`agent_cc_call` 出现 RINGING→ANSWERED→...→ENDED 完整轨迹；`agent_cc_call_event` 事件序号单调；挂机后 CDR 与录音 key 落库；重复事件不产生重复记录。

### P2 — 控制面 + 坐席面（转人工闭环）

目标：IVR 决策"转人工"→ 真实下发到 mod_callcenter 队列 → 坐席话机振铃 → 状态机进入 WAITING_AGENT/AGENT_SERVING；负面情绪自动转接从 `PENDING_NO_CHANNEL` 变成真实生效。

1. 新建 `FsChannelControl implements ChannelControl`，映射：`answer→answer`、`playAudio→uuid_broadcast`、`interruptPlayback→uuid_break`、`transferToQueue→uuid_transfer ... callcenter`、`startRecording→uuid_record`、`hangup→hangup`。
2. **补生产会话入口**：新增会话监听器，在 `call.answer` 后调 `IvrSessionDriver.drive(...)`，把真实 `FsChannelControl` 注册进 `LiveChannelRegistry`，挂机时注销。这是当前最大的断点，不做则 P3 引擎永远空转。
3. 坐席绑定：在坐席签入流程里把 `agent_cc_call_seat_state.extension` 与 mod_callcenter 成员绑定，`work_no` 作为业务唯一键；`callcenter::agent_state` 事件驱动 `ready_state`。
4. 扩展 `CsVoiceGateway` 契约（C6 预留）：新增 `originate`/`transfer` 默认方法，Mock 与真实各自实现；`number-provider`/`trunk-provider` 从死配置改成按配置选实现（仍可默认 Noop）。

验收：`direct-*` 规则触发后，FS 侧 `show calls` 能看到 `uuid_transfer` 到队列、坐席话机振铃；`agent_cc_call.status` 到 WAITING_AGENT→AGENT_SERVING；转写时间轴出现 TRANSFER_RING/TRANSFER_CONNECT 段。

### P3 — 媒体面（C2-real + C7，高风险）

目标：真实音频流接入，ASR 实时转写、TTS 播报、barge-in 打断。

1. 在 FusionPBX 上编译加载 **`mod_audio_stream`**（仓库 `github.com/amigniter/mod_audio_stream`，240 star，AGPL-3.0，2026-10 仍活跃；需 `libfreeswitch-dev/libssl-dev/zlib1g-dev/libspeexdsp-dev` + git submodule 拉 ixwebsocket，cmake 编译，`modules.conf.xml` 里 `<load module="mod_audio_stream"/>`）。拨号计划/Lua 里调用 `uuid_audio_stream start <wss-url> 16k mono <metadata>` 把音频推给 ForgeAgent 的 WebSocket 端点；停止用 `uuid_audio_stream stop`，打断用 `uuid_audio_break`。注意它是**第三方社区模块、非 FreeSWITCH 核心**，且 AGPL-3.0 协议需在合规评审后使用。
2. 新建 `FsMediaStreamProvider`/`FsMediaStream`：`open(uuid)` 建双工 WS；`onCallerAudio` 输出 8k/16bit/mono PCM 给 `asr.feed`；`writeTts` 推 TTS 音频；`interruptPlayback` 调 `uuid_break`。
3. 新建 `DashScopeAsrEngine`/`DashScopeTtsEngine` 实现 SPI，按 `agent.callcenter.asr-engine/tts-engine=dashscope` 装配；API key 走环境变量，不落库。
4. 复用 `CascadeTurnEngine` + `VoiceEngineRegistry` + `no-interrupt` 词组，编排不改。

**降级路径（务必先做）**：若 `mod_audio_stream` 编译/时钟同步受阻，先做"挂机后离线 ASR"——`fetchRecording` 取回录音 → 离线转写 → 写 `agent_cc_call_segment` → 洞察/质检照常跑。放弃实时打断，但业务闭环（转写/质检/画像/指标）完整，可先上线。

验收：8k PCM 单向延迟与时钟稳定（无爆音/堆积）；ASR final 回调写入转写段；TTS 播放期间说"嗯/好的"不打断，说实质内容触发 barge-in。

### P4 — 外呼与 PSTN 收口（C6/C9，资质后）

前置：SIP 中继资质 + 号码资源到位。**资质前不写任何真实 PSTN 呼出上线代码**。内容：`originate` 真实实现、`agent_cc_out_task`/`agent_cc_out_task_call` 建表（当前无 `agent_cc_c6.sql`）、号码池/中继 Provider 落地、拨打时段/频次/黑名单合规门禁、真实呼入呼出验收。

---

## 五、配置项清单（追加到 application.yml 的 agent.callcenter 段）

```yaml
# ① ESL starter 自带配置（前缀由库固定，不是 agent.callcenter 下）
link:
  thingscloud:
    freeswitch:
      esl:
        inbound:
          default-password: ${FS_ESL_PASSWORD}   # 覆盖默认值 ClueCon
          servers:
            - host: 127.0.0.1             # 内网地址，禁止公网
              port: 8021
          events:                          # 标准事件；CUSTOM 子类单独列
            - CHANNEL_CREATE
            - CHANNEL_ANSWER
            - CHANNEL_HANGUP_COMPLETE
            - DTMF
          custom-events:                   # 需 FS 侧 dialplan/Lua 按映射表发射
            - forgeagent::asr_result
            - forgeagent::bot_utterance
            - forgeagent::transfer
            - forgeagent::record_ready
            - forgeagent::cdr
            - callcenter::agent_state

# ② ForgeAgent 业务配置
agent:
  callcenter:
    provider: FS                      # prod 真实；dev 可 FS_MOCK
    mock-enabled: false               # prod 必须为 false
    freeswitch:
      audio-ws-host: 127.0.0.1        # 媒体面 P3，mod_audio_stream 监听
      audio-ws-port: 10000
    asr-engine: dashscope             # P3
    tts-engine: dashscope
    recording-store: local-fs         # 或 oss
    auto-transfer-on-negative: false
```

> 注：starter 2.2.0 的 `InboundClientProperties` 里 `defaultPassword` 默认 `ClueCon`；prod 必须显式覆盖，并在 `CallcenterProductionReadinessValidator` 中拦截默认密码。配置键以拆 jar 得到的 `spring-configuration-metadata.json` 为准（见第十节）。

安全纪律：`FS_ESL_PASSWORD` 走环境变量，不落库不进代码；prod 校验器补 ESL 密码缺失/默认值拒绝启动（沿用现有校验器模式）。

---

## 六、代码改造清单（精确文件）

| 动作 | 文件（相对 `backend/forge-modules/forge-callcenter/src/main/java/com/forgeagent/callcenter/`） |
|---|---|
| 新增 | `gateway/FsGateway.java`、`gateway/FsEslEventListener.java` |
| 新增 | `engine/FsChannelControl.java`、`engine/FsMediaStreamProvider.java`、`engine/FsMediaStream.java` |
| 新增 | `engine/DashScopeAsrEngine.java`、`engine/DashScopeTtsEngine.java`、`service/LiveSessionStarter.java`（生产会话入口） |
| 新增 | `config/FreeswitchProperties.java` |
| 修改 | `config/CallcenterProperties.java`、`config/CallcenterConfiguration.java`（Provider 按配置选择）、`config/CallcenterProductionReadinessValidator.java`（ESL 校验） |
| 修改 | `gateway/CsVoiceGateway.java`（加 originate/transfer 默认方法）、`controller/CsVoiceCallbackController.java`（或新增 ESL 入口 Controller） |
| 修改 | `pom.xml`（ESL 依赖）、`forge-admin/.../application.yml`（配置段） |
| 测试 | 新增 `@Tag("dev")` 契约测试 + 真机集成测试（开关 `-Dfs.it=true`，默认 skip）；同步调整 `CallEventDispatcherRoutingTest` 对 FS 的断言 |
| 数据 | 新增 `backend/sql/agent_cc_c6.sql`（外呼/号码池/中继，P4）；坐席 extension 绑定用现有 `agent_cc_call_seat_state.extension`，无需加列 |

---

## 七、验收标准（总）

1. **真机可复现**：内部分机呼入全流程 8 态轨迹完整，CDR/录音落库，重复事件幂等。
2. **不破坏 Mock**：`FS_MOCK`/`AICC_MOCK` 全量回归 188 条 callcenter 用例 + 后端 696 条不回退；新增类必须 `@Tag("dev")`。
3. **prod 门禁**：Mock provider / ESL 默认密码 / 缺密钥 → 拒绝启动。
4. **转人工生效**：`PENDING_NO_CHANNEL` 消失，真实 transfer 到达 mod_callcenter 队列。
5. **安全**：ESL 8021 仅内网可达，公网不可连；无硬编码密钥。

---

## 八、关键决策点（需拍板）

| # | 决策 | 选项 | 状态 / 建议 |
|---|---|---|---|
| 1 | 部署拓扑 | A 后端上云同内网 / B 本地+VPN 隧道 | **已定（A）**：FusionPBX 在云主机（公网IP），ForgeAgent 后端部署到**同一台云主机**，ESL 走 `127.0.0.1:8021`。本地开发机不直连 FS。 |
| 2 | ESL 客户端库 | ① `link.thingscloud:freeswitch-esl-spring-boot-starter:2.2.0` ② `io.github.qtstack:...:1.0.0`（Java 25，排除） | **已定**：用①（见第十节），已进 pom 并验证 Netty 单一 4.2.19 |
| 3 | 媒体面 | 实时双工（mod_audio_stream） / 离线 ASR 降级 | **已定（实时双工）**：在 FusionPBX（FS 1.10.12）编译 mod_audio_stream，走 WebSocket L16 PCM 实时双工；离线 ASR 仅作编译受阻时的应急降级。AGPL-3.0 需协议评审。 |
| 4 | 坐席话机 | FusionPBX 自带 WebRTC / Zoiper/MicroSIP | **已定**：两者都用，web 坐席用自带话机，开发调试用软话机 |
| 5 | 外呼范围 | 资质前只做呼入 / 同步做外呼表但不上线 | **已定**：无 SIP 中继资质与号码资源，**呼入优先，外呼（C6/C9 真实拨号）冻结**，不写真实 PSTN 呼出上线代码 |

### 8.1 已确认的环境参数（2026-11-17 用户提供；密钥不落文档）

| 项 | 值 | 用途 |
|---|---|---|
| FreeSWITCH 版本 | **1.10.12** | 决定 `mod_audio_stream` 编译匹配的 headers/版本；社区版需源码编译该模块 |
| FusionPBX 前端 | `https://<FusionPBX管理端地址>` | Web 管理界面（非对接端点） |
| 后端 IP | `云主机公网IP`（云主机，与 FS 同机） | ESL host 配 `127.0.0.1:8021`；本地开发机不直连 FS，用 `FS_MOCK` |
| ESL 密码 | 用户提供，**仅经环境变量 `FS_ESL_PASSWORD` 注入**，不写入代码/SQL/文档/记忆 | 覆盖 starter 默认值 `ClueCon`；prod 校验器拦截默认值 |
| 外呼资质 | 无 SIP 中继资质、无号码资源 | P4/C6/C9 真实拨号冻结，仅做呼入 |

---

## 九、风险与红线

- **ESL 安全**：8021 是全权控制面，公网暴露 = 任意呼出/挂机。必须内网 + 改密 + ACL/防火墙三重。
- **`mod_audio_stream` 编译与协议**：第三方社区模块（非 FreeSWITCH 核心，AGPL-3.0），FusionPBX apt 安装的 FreeSWITCH 需源码编译匹配版本；时钟/背压是常见坑，Pin 版本、单独验证音频环回后再接业务。**旧模块 `mod_audio_fork` 已废弃（原 drachtio 仓库 404）**，不要参考网上基于它的教程。若编译或协议评审受阻，走本节 P3 的离线 ASR 降级路径。
- **自定义事件发射**：`forgeagent::*` 不会自动产生，FS 侧 Lua/dialplan 必须按映射表发射，否则状态机缺 asr/cdr 事件——这是最容易被忽略的"接上了但不动"故障。
- **FR 红线**：资质前不写真实 PSTN 呼出上线代码；不做坐席 WebRTC 软电话（用 FusionPBX 自带）；不引 jpower/asterisk-java 等第三方呼叫框架。
- **文档代差**：SQL 只到 `agent_cc_c8`，无 c6/c7；docs/07 规划与落地之间存在上述缺口，本文已逐条标注，实施时按本文清单核对。

---

## 十、ESL 依赖选型核验（2026-11-17 实测，已定稿）

### 10.1 勘误

`docs/07` 第 32 行写 `link.thingsboard:freeswitch-esl`，**thingsboard 这个 group 不存在**（Maven Central 上 0 个 artifact）。正确 group 是 `link.thingscloud`。

### 10.2 候选核验结果（查 Maven Central 元数据 + 拆 jar 实测）

| 候选 | 版本 | Java | Boot 支持 | 结论 |
|---|---|---|---|---|
| `link.thingscloud:freeswitch-esl-spring-boot-starter` | **2.2.0**（latest，2024-12-19） | class major=52（Java 8 字节码） | `AutoConfiguration.imports`（Boot 2.7+/3/4 通用） | ✅ **选用** |
| `io.github.qtstack:freeswitch-esl-spring-boot-starter` | 1.0.0 | **要求 Java 25**（`maven.compiler.release=25`） | Boot 4.x only | ❌ 项目是 Java 21，加载即 `UnsupportedClassVersionError`；且仓库 2026-10 新建、1 star |

要点：
- thingscloud starter 2.2.0 的自动配置入口是 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（已拆 jar 确认），**不是旧的 `spring.factories`**，Boot 4.1.2 能正常加载。配置前缀 `link.thingscloud.freeswitch.esl.inbound.*`（如 `servers[0].host/port/password`、`events`）。
- 它传递依赖 `freeswitch-esl:2.2.0`（Netty 4.1 系列：`netty-handler`/`netty-transport`/native epoll+kqueue，版本由父 POM 的 `spring-boot-dependencies:2.7.16` 管理）。

### 10.3 Netty 版本裂缝：已解决（2026-11-17 落地）

- 项目父 POM（`backend/pom.xml`）是 **Spring Boot 4.1.2**，BOM 把 Netty 管理到 **4.2.19.Final**；而 ESL 库 2.2.0 按 Netty 4.1 编译并传递引入 4.1 系列（含 native epoll/kqueue）。
- **实测 ESL 库字节码**：`AbstractNettyInboundClient.class` 只用 `NioEventLoopGroup` / `NioSocketChannel`，**不引用任何 epoll/kqueue native 类**，因此 native 依赖可安全排除。
- **已在 `forge-callcenter/pom.xml` 落地**（本次改动）：
  - 引入 `freeswitch-esl-spring-boot-starter:2.2.0`，exclusions 排除 `netty-handler` / `netty-transport` / `netty-transport-native-epoll` / `netty-transport-native-kqueue` 四个 4.1 依赖；
  - 显式引入 `io.netty:netty-handler`（不写版本，由 Boot BOM 管），补齐 transport/buffer/codec/common。
- **验证结果**：`dependency:tree` 全树 Netty 单一版本 **4.2.19.Final**，无 4.1.x、无 `omitted for conflict`；`compile` BUILD SUCCESS；callcenter **151 条测试 0 失败**。

### 10.4 Maven 依赖片段（已采用）

```xml
<dependency>
    <groupId>link.thingscloud</groupId>
    <artifactId>freeswitch-esl-spring-boot-starter</artifactId>
    <version>2.2.0</version>
    <exclusions>
        <exclusion><groupId>io.netty</groupId><artifactId>netty-handler</artifactId></exclusion>
        <exclusion><groupId>io.netty</groupId><artifactId>netty-transport</artifactId></exclusion>
        <exclusion><groupId>io.netty</groupId><artifactId>netty-transport-native-epoll</artifactId></exclusion>
        <exclusion><groupId>io.netty</groupId><artifactId>netty-transport-native-kqueue</artifactId></exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-handler</artifactId>
</dependency>
```

用 starter 后，P1 通过实现 `EslEventHandler` + `@EslEventName` 注解接入事件（**不要**自己定义 `IEslEventListener` bean——starter 的 `IEslEventListenerTemplate` 已是该 bean，自定义会冲突）。starter 自动建连与重连，业务侧无需管理连接；配置示例见第十一节。docs/07 第 32 行的 `thingsboard` 笔误已于 2026-11-17 更正为 starter 坐标。

---

## 十一、P1 实施成果（2026-11-17 已落地并验证）

### 11.1 已交付代码

| 文件 | 作用 |
|---|---|
| `gateway/FsGateway.java` | 真实网关：`supports("FS")`；`verify` 恒 true（ESL 连接级认证已由 starter 完成）；`parse` 复用 `FsEventNormalizer`；`fetchRecording` 支持本地文件/`file://`/`mock://`，含目录穿越防护 |
| `engine/FsEslEventHandler.java` | `@EslEventName({"CHANNEL_CREATE","CHANNEL_ANSWER","CHANNEL_HANGUP_COMPLETE","DTMF","CUSTOM"})`，把 `EslEvent` 转成 FsEventNormalizer 期望的 `{"events":[...]}` 报文；CUSTOM 事件用 `Event-Subclass` 还原子类名 |
| `service/CallEventDispatcher.java` | 新增 `dispatchTrusted(provider, channelCode, rawBody)`：跳过 HMAC/时间窗/nonce 三关，保留渠道校验+路由+归一化+幂等+状态机；原 `dispatch()` 抽出 `consume()` 共用 |
| `config/CallcenterProperties.java` | 新增 `freeswitch.channel-code`（默认 CC_FS）、`freeswitch.recording-dir` |
| `config/CallcenterProductionReadinessValidator.java` | prod + provider=FS 时校验 channel-code 必填、ESL 默认密码 `ClueCon` 未改则拒绝启动（只读配置键，不落明文） |
| `application.yml` | 补 `agent.callcenter.freeswitch.*` 业务配置（未改 dev 的 provider） |

**测试**：新增 `FsGatewayTest`(7) + `FsEslEventHandlerTest`(6)，均 `@Tag("dev")`；callcenter 测试 151→**164 条，0 失败**；`compile` BUILD SUCCESS；Netty 全树单一 4.2.19。

### 11.2 云主机部署配置样例（密码走环境变量，勿写明文）

在云主机上用环境变量覆盖（`FS_ESL_PASSWORD` 由用户注入，不落代码/SQL/文档）：

```yaml
# application-prod.yml 或启动参数覆盖
link:
  thingscloud:
    freeswitch:
      esl:
        inbound:
          default-password: ${FS_ESL_PASSWORD}
          servers:
            - host: 127.0.0.1        # 后端与 FS 同机,走回环;ESL 不监听公网
              port: 8021
          events:
            - CHANNEL_CREATE
            - CHANNEL_ANSWER
            - CHANNEL_HANGUP_COMPLETE
            - DTMF
            - CUSTOM               # 自定义事件按 Event-Subclass 还原

agent:
  callcenter:
    enabled: true
    provider: FS
    mock-enabled: false
    freeswitch:
      channel-code: CC_FS          # 对应 agent_cc_channel_config 中 media_provider=FS 的渠道
      recording-dir: /var/lib/freeswitch/recordings
```

### 11.3 用户侧待办（阻塞真机联调，非代码）

1. 在 FusionPBX 上把 ESL 密码设为 `FS_ESL_PASSWORD` 的值，ESL 只监听 `127.0.0.1`，防火墙不开放 8021。
2. 建 `media_provider=FS`、`channel_code=CC_FS` 的语音渠道记录（`agent_cc_channel_config`）。
3. 建内部分机 021800001/021800002 + `after_sales` 队列，配呼入→IVR→转人工拨号计划。
4. 在 dialplan/Lua 按 `FS_EVENT_TYPE_MAP` 发自定义事件（`forgeagent::asr_result/cdr/record_ready/transfer`、`callcenter::agent_state`）。
5. 部署后端到云主机，注入 `FS_ESL_PASSWORD`，用真实分机呼入冒烟。

> 详细页面步骤见 `10-FusionPBX页面操作手册.md`。

---

## 十二、P2 实施成果（2026-11-17 已落地并验证）

### 12.1 交付范围与边界

| 能力 | 状态 | 说明 |
|---|---|---|
| 通道控制（answer/转人工/挂机/录音/打断） | ✅ 真实下发 | `FsChannelControl` 走 ESL `uuid_*` |
| 生产会话入口（登记真实通道） | ✅ 已补 | `FsChannelRegistrar`，消除 `PENDING_NO_CHANNEL` |
| 转人工闭环 | ✅ 打通 | 规则/负面情绪 → `uuid_transfer` → mod_callcenter 队列 |
| 媒体面（播放 PCM / barge-in） | ⏳ P3 | `playAudio` 当前只记日志，等 `mod_audio_stream` 下行 |
| IVR 语音节点（answer/received） | ⏳ P3 | 需要 `MediaStream`，与媒体面同批 |

**关键设计判断**：`DirectAnswerRouter` / `InsightPipeline` 的转人工只依赖 `ChannelControl`，不依赖 `MediaStream`。因此 P2 不需要媒体面就能让转人工真实生效；IVR 的语音交互才需要，留到 P3。

### 12.2 新增代码

| 文件 | 作用 |
|---|---|
| `engine/FsChannelControl.java` | 实现 `ChannelControl`：`uuid_answer` / `uuid_transfer <号码> XML <context>` / `uuid_kill` / `uuid_record start` / `uuid_break all`；`playAudio` 降级（P3 媒体面） |
| `engine/FsChannelRegistrar.java` | 接通时创建并登记真实通道，ENDED 时注销；仅 `media_provider=FS` 且 ESL 可用时生效，其余静默跳过 |
| `domain/CsCallStatus.java` | 新增 `wasAnswered()`（rank ≥ ANSWERED 且非 ENDED） |
| `service/CallEventDispatcher.java` | 新增 `syncLiveChannel()` 挂点，在状态推进后同步通道生命周期 |
| `config/CallcenterProperties.java` | `freeswitch.esl-address` / `dialplan-context` / `queue-extensions` |
| `application.yml` | 补上述三项配置（`queue-extensions: after_sales:888888`） |

**测试**：新增 `FsChannelControlTest`(9) + `FsChannelRegistrarTest`(6)；callcenter 164→**179 条，0 失败**。

### 12.3 转人工命令口径（实现已按此编码）

`mod_callcenter` 的入场方式是 **dialplan 中某号码执行 `callcenter` 应用**，所以转人工不是直接转队列名，而是：

```
uuid_transfer <channel-uuid> <队列对应号码> XML default
```

队列名到号码的映射由 `agent.callcenter.freeswitch.queue-extensions` 配置（默认 `after_sales:888888`）。未配置的队列名按原值当号码转。

**因此 FusionPBX 侧还需补一步**：在 dialplan 中新增号码 `888888` 的规则，执行 `callcenter` 应用：

- `Dialplan → Dialplan Manager → +`
- **Name**：`CC_Queue_Entry`，**Context**：`default`（与 `dialplan-context` 一致）
- **Condition**：`destination_number` = `^888888$`
- **Actions**（按序）：
  1. `answer`
  2. `callcenter`，Data = `after_sales@default`
- **Save → Reload XML**

> 若你更愿意直接用队列名，也可把 `queue-extensions` 留空，并在 dialplan 里用 `^after_sales$` 做条件——但号码方式更稳，推荐 `888888`。

### 12.4 P2 联调验收

1. 分机 021800002 拨 `999999` → 021800001 响铃、接通（P1 已通）。
2. 后端日志出现 `[CC-ESL] 已登记真实通道 callId=...`。
3. 触发转人工（规则命中或负面情绪），日志出现：
   - `[CC-ESL] 已下发转人工 uuid=... queue=after_sales target=888888 context=default`
   - FS 侧 `fs_cli -x "callcenter_config queue list members after_sales@default"` 能看到排队的呼叫
4. `agent_cc_call.status` 进入 `WAITING_AGENT`；坐席接通后 `AGENT_SERVING`，转写时间轴出现 TRANSFER_RING/TRANSFER_CONNECT。
5. 挂机后日志出现 `[CC-ESL] 已注销通道`，`LiveChannelRegistry` 归零。

---

## 十三、P3 实施成果（媒体面，2026-11-17 已落地并验证）

### 13.1 方向性事实（避免搞反）

**FreeSWITCH 是 WebSocket 客户端，主动连 ForgeAgent**——`uuid_audio_stream start ws://<ForgeAgent>/cc/media/<callId>`。所以 ForgeAgent 起的是 **WebSocket 服务端**（`/cc/media/**`），不是去连 FS。

协议（已核实 mod_audio_stream README + DeepWiki）：
- 上行：二进制帧，PCM16LE，8k/16k，mono，无容器头
- 下行：JSON 文本帧 `{"type":"streamAudio","data":{"audioDataType":"raw","sampleRate":8000,"audioData":"<base64>"}}`
- 打断：文本帧 `{"type":"clearAudio"}`

### 13.2 新增代码

| 文件 | 作用 |
|---|---|
| `engine/FsMediaStream.java` | `MediaStream` 实现：上行帧→ASR 回调；下行 PCM→base64 streamAudio；`clearAudio` 打断；连接前缓冲、连上补发；`isBotPlaying` 按字节时长估算 |
| `engine/MediaStreamHub.java` | WebSocket 服务端（`AbstractWebSocketHandler`）：按 URI 末段取 callId，二进制帧转 `onCallerFrame`；连接/`open` 时序解耦 |
| `engine/FsMediaStreamProvider.java` | `MediaStreamProvider` 真实实现，与 Mock 用 `media-stream-provider` 配置互斥 |
| `config/CallcenterMediaWebSocketConfig.java` | 注册 `/cc/media/**` 端点（仅 fs 模式装配） |
| `engine/FsChannelRegistrar.java` | 接通时额外下发 `uuid_audio_stream start <ws-url> mono 8k` |
| `pom.xml` | 加 `spring-boot-starter-websocket`（Boot 4.1.2 → spring-websocket 7.0.10） |

**测试**：新增 `FsMediaStreamTest`(7) + Registrar 补 2 条；callcenter 179→**188 条，0 失败**。

### 13.3 配置

```yaml
agent:
  callcenter:
    media-stream-provider: fs          # mock / fs,决定音频管道实现
    freeswitch:
      media-ws-base: ws://127.0.0.1:8080   # FS 能访问到的本服务地址
      media-sample-rate: 8k                # 与 MediaStream/AsrEngine 接口口径一致
```

### 13.4 P3 仍未完成（不谎称已做）

| 项 | 状态 | 阻塞原因 |
|---|---|---|
| 真实 ASR 引擎（DashScope） | ❌ 未实现 | 需要 API key；接口 SPI 已留位（`AsrEngine`），`asr-engine=dashscope` 时 `VoiceEngineRegistry` 会报未装配 |
| 真实 TTS 引擎（DashScope） | ❌ 未实现 | 同上；当前 `tts-engine=mock` 下会走静音脚本 |
| IVR 会话自动驱动 | ❌ 未接 | `IvrSessionDriver.drive()` 需业务决定「何时启动哪条 IVR 流程」（routeCode），当前无自动触发点 |

> 结论：**音频管道（进/出/打断）已通，但"听懂"和"说话"还需要接真实 ASR/TTS**。这两项要 API key，不能用 mock 假装完成。

### 13.5 用户侧待办（P3）

1. 在 FusionPBX 编译并加载 `mod_audio_stream`（AGPL-3.0，需协议评审），`modules.conf.xml` 加 `<load module="mod_audio_stream"/>`，`fs_cli -x 'module_exists mod_audio_stream'` 验证。
2. FusionPBX 与后端同机，`media-ws-base` 用 `ws://127.0.0.1:<后端端口>`；若不同机改成内网地址。
3. 确认后端端口（默认 8080）与 `media-ws-base` 一致，防火墙放行同机回环即可（不开放公网）。
4. 提供 ASR/TTS 选型与 API key（DashScope 或其他），才能完成 P3 剩余两项。
