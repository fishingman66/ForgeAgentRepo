# forge-callcenter · 语音呼叫中心

对接华为云 AICC（当前为 `AICC_MOCK` 契约实现）的语音域：回调接入、8 态状态机、转写与录音入库、通话↔会话↔工单业务打通、坐席状态与 ACD、通话日指标与合规擦除。

模块开关 `agent.callcenter.enabled`（默认 `false`），关闭时 Bean 与 `/chat-voice/**` 端点不装配。

## 包结构

```
com.forgeagent.callcenter
├── controller   # CsVoiceCallbackController（/chat-voice/{provider}/{code}）、CsCallController（坐席侧查询/录音取回）
├── gateway      # CsVoiceGateway 接口 + AiccGateway 实现 + VoiceProvider 事件映射 + CsVoiceEvent 归一化事件
├── service      # 回调编排、幂等、状态机、转写入库、录音入库、话单对账、坐席状态、业务绑定、质检、指标、擦除
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
POST /chat-voice/{provider}/{code}         AICC 回调（免登录，HMAC 验签）
GET  /agent/customerservice/calls?status=  通话列表（坐席侧）
GET  /agent/customerservice/calls/{id}     详情：主记录 + 事件轨迹 + 转写时间轴
GET  /agent/customerservice/calls/{id}/recording   短期签名 URL
GET  /agent/customerservice/calls/stats    当日通话指标
GET  /agent/customerservice/calls/call-seats       坐席就绪态
```

## 配置

`agent.callcenter.*`（`CallcenterProperties`）：`enabled`、`media-provider`（`AICC_MOCK` / `AICC`）、`callback-secret` 与 `callback-tolerance-seconds`、`recording-store`（`local-fs` / `oss`）、录音留存天数、音频出域开关、D8 规模（坐席数 / 并发路数 / 月分钟预算）、号码与中继实现。

生产环境 `CallcenterProductionReadinessValidator` 会拒绝 Mock 供应商与默认密钥。

## 测试

5 类 19 用例，全部 `@Tag("dev")`：

```bash
mvn -Pdev test \
  "-Dtest=AiccGatewayTest,CallStateMachineTest,CallFingerprintsTest,LocalFsRecordingStorageTest,CallBusinessBinderTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" \
  -pl forge-modules/forge-callcenter -am
```

> 根 pom 默认 `maven.test.skip=true`，两个覆盖参数必须同时写；验收以 surefire 报告的 `tests/failures/errors` 为准，不认 EXIT=0。

| 测试 | 覆盖 |
| --- | --- |
| `AiccGatewayTest` | 验签、解析、录音取回（5） |
| `CallStateMachineTest` | 8 态跃迁与乱序不回退（5） |
| `CallFingerprintsTest` | 跨语言一致的事件指纹（3） |
| `LocalFsRecordingStorageTest` | 本地存储读写与签名下载（3） |
| `CallBusinessBinderTest` | 挂机结案序列（3） |

端到端脚本：`temp/v1_verify.py`（25/25，回调契约与状态机）、`temp/v2_verify.py`（29/29，业务打通：建会话→建单→ACD→挂机结案→CSAT→质检→指标→擦除级联）。回调发送端为 `temp/mock_callback_sender.py`，11 个 fixture 在 `src/test/resources/mock/aicc/`。

## 扩展点

- **接入真实 AICC**：只换 `AiccGateway` 的 `verify` / `parse` / `fetchRecording` 三个方法，业务层零改动（V0-real）。
- **外呼**：`NumberProvider` / `TrunkProvider` 已留接口，当前 noop。
- **新增事件类型**：供应商映射表加一行 + `CallStateMachine` 加目标态 + `CallEventDispatcher.applyEvent` 加一个分支，其余阶段零改动。
- **坐席辅助联动**：`call.asr_result` 落库后会调用文本域的 `SeatAssistService.suggestForVoice`（A2），语音侧不感知推荐实现细节。
