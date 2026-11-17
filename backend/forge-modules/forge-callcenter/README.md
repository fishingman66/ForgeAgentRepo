# forge-callcenter · 语音呼叫中心

语音域：回调接入、8 态状态机、转写与录音入库、通话↔会话↔工单业务打通、坐席状态与 ACD、通话日指标与合规擦除。两套供应商链路并存：

- **AICC**（华为云，当前 `AICC_MOCK`）：HTTP 回调 `/chat-voice/{provider}/{code}`
- **FreeSWITCH**（自建，provider=`FS`）：ESL 事件订阅 + `mod_audio_stream` 媒体 WebSocket，2026-11-17 已交付 P1 事件面 / P2 控制面 / P3 媒体面，详见 `docs/09`

模块开关 `agent.callcenter.enabled`（默认 `false`），关闭时 Bean 与 `/chat-voice/**` 端点不装配。真 FS 链路另由 `agent.callcenter.media-stream-provider=fs` 切换媒体实现（默认 `mock`）。

## 包结构

```
com.forgeagent.callcenter
├── controller   # CsVoiceCallbackController（/chat-voice/{provider}/{code}）、CsCallController（坐席侧查询/录音取回）
├── gateway      # CsVoiceGateway 接口 + AiccGateway / FsGateway 实现 + FsEventNormalizer 事件映射 + CsVoiceEvent
├── engine       # 通道控制(FsChannelControl)/ 媒体流(FsMediaStream + MediaStreamHub)/ ASR-TTS SPI / IVR
├── route        # 直接应答分流与意图裁决(转人工下发走 LiveChannelRegistry)
├── insight      # 洞察管道(负面自动转接经 LiveChannelRegistry 下发 uuid_transfer)
├── service      # 回调编排(dispatch + dispatchTrusted)、幂等、状态机、转写/录音入库、话单、业务绑定
├── domain       # CsCall / CsCallEvent / CsCallSegment / CsCallChannelConfig / CsCallSeatState / CsCallStatus
├── storage      # RecordingStorage 接口：local-fs（开发）/ oss（生产）
├── provider     # NumberProvider / TrunkProvider（D8 暂为 noop）
├── config       # CallcenterProperties、条件装配、生产就绪校验
└── task         # 录音留存清理
```

依赖方向：`forge-callcenter → forge-customer-service → forge-callcenter-common`，单向不回指。本模块复用文本域的 `CsTicketService` / `CsVisitorService` / `CsatService` / `CsQualityService`，语音侧不重复实现这些能力。

## 回调链路（七个阶段）

`CallEventDispatcher.dispatch` 是一次回调的总入口，固定按序执行：

1. **渠道解析** — `agent_cc_channel_config` 按 `channel_code` 取验签配置（与文本域 `agent_cs_channel` 同码并存，缺一不可）
2. **时间窗 + nonce** — 超出 `callback-tolerance-seconds` 或 nonce 重放直接拒
3. **验签** — `HMAC-SHA256(timestamp + "\n" + nonce + "\n" + rawBody)`，Base64URL 无填充
4. **报文归一化** — `VoiceProvider` 把供应商事件名映射成 `call.ring` / `call.asr_result` / `call.hangup` 等
5. **逐事件幂等** — `(call_id, event_seq, event_type)` 唯一键，重复事件短路
6. **状态推进 + 分派** — `CallStateMachine.targetOf` 计算目标态，`applyEvent` 按事件语义落副作用
7. **业务绑定 + 弹屏** — 挂机钩子（结案 / 质检 / 指标）与 `CALL_*` 事件下行

对外响应固定 `{message, retcode}` 且 HTTP 200，内部异常一律不外抛；失败信息对外模糊（不区分签名错/渠道错），细节只进内部日志与死信轨迹。

## 状态机

`RINGING → ANSWERED → … → ENDED` 共 8 态，`CsCallStatus.advance(from, target)` 单调推进：乱序或迟到的 CDR 只补字段，不回退状态。挂机结案钩子只在 `ENDED` 首次达成时触发（避免迟到 CDR 重复跑质检与指标）。

## 业务绑定

`CallBusinessBinder` 是通话与文本域关联的唯一写入口：

- `ring` → 用同码文本渠道创建客服会话，回填 `conversation_id`
- `transfer` → `CsTicketService.requestHandoff` 建工单并入 ACD 队列
- 坐席 `READY` → 触发一次 ACD 自动分配
- `hangup` → 按合法跃迁表结案（`PROCESSING → WAITING_CONFIRM → RESOLVED`），RESOLVED 自动发 CSAT 邀请
- 挂机后串起转写质检（`source=VOICE_TRANSCRIPT`）与通话日指标重算

## 录音

`call.record.ready` 一次性取回音频入存储，库里只存对象键、不存云侧 URL。`RecordingStorage` 两个实现：

| 实现 | 场景 | 特点 |
| --- | --- | --- |
| `local-fs` | 开发 / 无真实 OSS | 本地目录 + HMAC 签名下载 + 路径穿越防护 |
| `oss` | 生产 | 走 `sys_oss_config` 与 `forge-common-oss` |

`agent.callcenter.recording-store` 一个配置切换，对象键规则一致，业务代码零改动。坐席访问走短期签名 URL 并写 `RECORDING_ACCESS` 审计；留存清理与隐私擦除（监听 `CsConversationErasedEvent`）都会删除录音对象并留痕。

## 端点

```
POST /chat-voice/{provider}/{code}         AICC / FS_MOCK 回调（免登录，HMAC 验签）
WS   /cc/media/{callId}                    FreeSWITCH mod_audio_stream 媒体接入（FS 主动连，仅内网）
GET  /agent/customerservice/calls?status=  通话列表（坐席侧）
GET  /agent/customerservice/calls/{id}     详情：主记录 + 事件轨迹 + 转写时间轴
GET  /agent/customerservice/calls/{id}/recording   短期签名 URL
GET  /agent/customerservice/calls/stats    当日通话指标
GET  /agent/customerservice/calls/call-seats       坐席就绪态
```

## 配置

`agent.callcenter.*`（`CallcenterProperties`）：`enabled`、`provider`（`AICC_MOCK` / `AICC` / `FS` / `FS_MOCK`）、`media-stream-provider`（`mock` / `fs`）、`callback-secret` 与 `callback-tolerance-seconds`、`recording-store`（`local-fs` / `oss`）、录音留存天数、音频出域开关、D8 规模（坐席数 / 并发路数 / 月分钟预算）、号码与中继实现。

真 FS 另需两段配置：ESL 连接走 starter 前缀 `link.thingscloud.freeswitch.esl.inbound.*`（密码用 `${FS_ESL_PASSWORD}`，绝不写明文）；业务侧 `agent.callcenter.freeswitch.*` 含 `channel-code`（默认 CC_FS）、`esl-address`（默认 127.0.0.1:8021）、`dialplan-context`、`queue-extensions`（队列名→号码，如 after_sales:5000）、`media-ws-base`（FS 可访问的本服务地址）、`media-sample-rate`。详见 `docs/09` 第五节。

生产环境 `CallcenterProductionReadinessValidator` 会拒绝 Mock 供应商、默认密钥、以及未改的 ESL 默认密码 `ClueCon`。

## 测试

16 类 188 用例，全部 `@Tag("dev")`：

```bash
mvn -Pdev test \
  "-Dtest=*GatewayTest,CallStateMachineTest,CallFingerprintsTest,LocalFsRecordingStorageTest,CallBusinessBinderTest,FsEslEventHandlerTest,FsChannelControlTest,FsChannelRegistrarTest,FsMediaStreamTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" \
  -pl forge-modules/forge-callcenter -am
```

> 根 pom 默认 `maven.test.skip=true`，两个覆盖参数必须同时写；验收以 surefire 报告的 `tests/failures/errors` 为准，不认 EXIT=0。

| 测试 | 覆盖 |
| --- | --- |
| `AiccGatewayTest` / `FsMockGatewayTest` | 验签、解析、录音取回 |
| `FsGatewayTest`（7） | 真实网关路由、归一化复用、本地录音与路径穿越防护 |
| `FsEslEventHandlerTest`（6） | EslEvent→业务报文、CUSTOM 子类还原、时间戳换算 |
| `FsChannelControlTest`（9） | ESL uuid 命令拼装、队列名→号码映射 |
| `FsChannelRegistrarTest`（8） | 接通登记/挂机注销、媒体流启动下发 |
| `FsMediaStreamTest`（7） | 上下行音频编码、缓冲补发、clearAudio 打断 |
| `CallStateMachineTest` / `CallFingerprintsTest` / `LocalFsRecordingStorageTest` / `CallBusinessBinderTest` | 8 态跃迁、指纹、录音、挂机结案 |

端到端脚本：`temp/v1_verify.py`（25/25，回调契约与状态机）、`temp/v2_verify.py`（29/29，业务打通：建会话→建单→ACD→挂机结案→CSAT→质检→指标→擦除级联）。回调发送端为 `temp/mock_callback_sender.py`，11 个 fixture 在 `src/test/resources/mock/aicc/`。

## 扩展点

- **FreeSWITCH 真实链路**（已交付 P1~P3）：四层 SPI 真实实现已填——`FsGateway`（事件源）、`FsChannelControl`（控制面）、`FsMediaStream`（媒体面）、`LiveChannelRegistry`（会话入口）。**未做**：真实 ASR/TTS 引擎（DashScope，需 API key）与 IVR 自动驱动（`IvrSessionDriver.drive` 无生产触发点），不能用 Mock 假装完成。
- **接入真实 AICC**：只换 `AiccGateway` 的 `verify` / `parse` / `fetchRecording` 三个方法，业务层零改动（V0-real，待 AICC 服务）。
- **外呼**：`NumberProvider` / `TrunkProvider` 已留接口，当前 noop；无 SIP 中继资质与号码资源前不写真实 PSTN 呼出上线代码。
- **新增事件类型**：供应商映射表加一行 + `CallStateMachine` 加目标态 + `CallEventDispatcher.applyEvent` 加一个分支，其余阶段零改动。
- **坐席辅助联动**：`call.asr_result` 落库后会调用文本域的 `SeatAssistService.suggestForVoice`（A2），语音侧不感知推荐实现细节。
