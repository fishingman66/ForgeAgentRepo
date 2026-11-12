# forge-callcenter-common · 客服契约层

文本客服与语音呼叫中心共用的**零业务依赖**契约层。编译期只依赖 JDK（不含 Spring、MyBatis、Jackson），任何业务模块的依赖进来都视为破坏——它存在的意义就是让两个域共享同一份协议定义，而不产生模块环。

## 内容

| 类 | 职责 |
| --- | --- |
| `CsEventEnvelope` | 统一事件信封 `{type, sequence, conversationId, ticketId, payload, traceId, ts}`，SSE / WebSocket 共用；`withSequence` 补齐单调序号 |
| `CsEventTypes` | 事件名唯一来源：文本 13 个（含 `cs_seat_suggest`）、语音 9 个 `CALL_*`，另有 `TEXT_EVENTS` / `VOICE_EVENTS` / `ALL_EVENTS` 三个集合 |
| `CsRealtimeTransport` | 通道抽象 `open / push / close`，实现留在 `forge-customer-service`（`SseRealtimeTransport`） |
| `CsChannelSignature` | 渠道回调验签：`HMAC-SHA256(timestamp + "\n" + nonce + "\n" + body)`，Base64URL 无填充；与 Python 调试端（`temp/mock_callback_sender.py`）逐字节对齐 |
| `CsPiiMasker` | 手机号 / 身份证 / 银行卡 / 邮箱脱敏，落库与推送前统一调用 |
| `CsChannelType` | 渠道类型常量（widget / h5 / wechat_mp / wecom / open_api / voice_inbound） |
| `CsScene` | 会话场景常量（文本客服 / 语音呼入） |
| `CsDefaults` | 跨域默认值（限流、留存、超时等） |

## 修改纪律

- **事件常量变化必须同步契约单测**：`CsContractConstantsTest` 锁定 `TEXT_EVENTS` / `VOICE_EVENTS` / `ALL_EVENTS` 的成员与数量，加常量不更新断言会直接红。
- **信封字段只增不改**：追加走 record 组件顺序追加（序列化兼容）；payload 结构由各域自行演进，但对外只增字段。
- **不做业务判断**：这里只放协议与纯函数。需要查库、需要 Spring 的东西一律留在业务模块。
- **PII 正则双向同步**：`CsPiiMasker` 的四类脱敏正则与 `forge-rag` 的 `KbPiiMasker` 逐字一致，改动必须两边同时改，否则同一份语料在客服链路与知识库清洗链路的打码结果会分叉。

## 测试

3 类 13 用例，全部 `@Tag("dev")`：

```bash
mvn -Pdev test \
  "-Dtest=CsContractConstantsTest,CsChannelSignatureTest,CsPiiMaskerTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false" \
  -pl forge-modules/forge-callcenter-common -am
```

> 根 pom 默认 `maven.test.skip=true`，两个覆盖参数必须同时写；验收以 surefire 报告的 `tests/failures/errors` 为准，不认 EXIT=0。

| 测试 | 覆盖 |
| --- | --- |
| `CsContractConstantsTest` | 事件常量集合与信封序列化（8） |
| `CsChannelSignatureTest` | 验签正确性、Base64URL 无填充、篡改检测（3） |
| `CsPiiMaskerTest` | 四类 PII 脱敏与幂等（2） |
