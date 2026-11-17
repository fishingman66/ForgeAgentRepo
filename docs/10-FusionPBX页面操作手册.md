# 附：FusionPBX 侧操作步骤（呼入联调）

> 配套 `09-智能坐席对接FreeSWITCH开发方案.md` 第十一节 11.3 的待办 2/3/4。
> 环境：FusionPBX 5.6 / FreeSWITCH 1.10.12，前端 `https://<FusionPBX管理端地址>`。
> 说明：**待办 2 不在 FusionPBX 页面做**（是 ForgeAgent 自己的数据库），待办 3/4 在 FusionPBX 页面做。
> 所有菜单路径基于 FusionPBX 5.6 官方结构；不同汉化包字段名可能略有差异，以英文原名为准。

---

## 待办 2：建语音渠道记录（在 ForgeAgent 库，不是 FusionPBX）

ESL 事件没有 URL 渠道段，业务侧靠 `channel_code=CC_FS` 找到渠道配置。这条记录落在 ForgeAgent 的 MySQL，两条路二选一：

### 方式 A：SQL 直接插入（推荐，快）

在云主机 MySQL（库 `forge-vue` 或你的实际库）执行：

```sql
INSERT INTO agent_cc_channel_config
  (channel_code, media_provider, service_number, workspace_id, agent_id,
   callback_secret, voice_config_json, rate_limit, status, del_flag, create_time, update_time)
VALUES
  ('CC_FS', 'FS', '021800001', 1, 0,
   NULL, NULL, 0, '0', '0', NOW(), NOW())
ON DUPLICATE KEY UPDATE media_provider='FS', status='0', update_time=NOW();
```

注意：
- `media_provider` 必须是 **`FS`**（不是 FS_MOCK），否则 `fetchRecording` 走不到 FsGateway。
- `status='0'` 表示启用（`CsVoiceCallbackGuard.require` 的判定）。
- `workspace_id`/`agent_id` 按你的实际空间与机器人填；这里先用 1/0 占位。
- 表结构以 `backend/sql/agent_cc_c0.sql` 为准，若列名不同按实际表调整。

### 方式 B：管理页面

 FusionPBX 页面**没有**这个概念。它是 ForgeAgent 自己的配置，走 ForgeAgent 后端接口或数据库；若前端「渠道接入」页（Channels.vue）没有 voice_inbound 选项，就只能用方式 A。

**验证**：ForgeAgent 启动时 ESL 事件若提示「渠道不可用 code=CC_FS」，就是这条记录没建或 status 非 0。

---

## 待办 3：FusionPBX 建分机 + 队列 + 呼入拨号计划

### 3.1 确认域名（只需一次）

1. 浏览器打开 `https://<FusionPBX管理端地址>`，用 admin 登录。
2. 顶部菜单 **Advanced → Domains**，确认有一个默认 domain（通常是公网 IP 或主机名）。
3. 单租户不用改；右上角下拉框选中该 domain，后续所有配置都在这个 domain 下。

### 3.2 建两个测试分机 021800001 / 021800002

1. 顶部菜单 **Accounts → Extensions**。
2. 右上角点 **+**（Add）。
3. 填写关键字段：
   - **Extension**：`021800001`
   - **Password**：设一个强密码（软话机注册要用，记下来）
   - **Effective Caller ID Name**：`Agent021800001`（可选）
   - **Effective Caller ID Number**：`021800001`（可选）
   - 其余保持默认，**Enabled = True**。
4. 点 **Save**。
5. 同样步骤再建 `021800002`。
6. 验证：菜单 **Status → SIP Status → Registrations**，分机注册后会出现在这里。

> 软话机（Zoiper/MicroSIP）注册参数：Server/Domain = `FusionPBX公网IP`（或你的 domain），Username = `021800001`，Password = 上一步设的，Transport = UDP。

### 3.3 建 Call Center 队列 after_sales

1. 顶部菜单 **Apps → Call Center**（若菜单里没有，先到 **Advanced → Modules** 确认 `call_center` 已启用）。
2. 右上角点 **+** 新增队列。
3. 关键字段：
   - **Name**：`after_sales`
   - **Extension**：`888888`（队列的拨号分机，系统内唯一，转人工时 `uuid_transfer` 到这个号）
   - **Strategy**：`longest-idle-agent`（或 `ring-all`，联调随便选）
   - **Tier Rules Apply**：False（单坐席测试不用分层）
   - 其余默认。
4. **Save**。
5. 右上角点 **Agents** → **+** 加坐席：
   - **Agent Name**：选 021800001 对应的用户/分机
   - **Type**：`callback`
   - **Contact**：选分机 `021800001`
   - **Agent ID**：`021800001`（**这个会作为业务侧 workNo**，与 `agent_cc_call_seat_state.work_no` 对应）
   - **Agent Password**：设置一个，坐席用它签入
6. 同样加 021800002。
7. 坐席签入：用分机话机拨 `*22`，按提示输入 Agent ID + 密码；或在 **Status → Agent Status** 页面签入。签入后状态为 Available/Ready。

### 3.4 建呼入拨号计划（内部分机呼入 → 队列）

> 目的：内部分机（如 021800002）拨一个测试号，能进队列并让坐席话机响铃，形成完整回路。**不碰 PSTN，不需要资质。**

1. 顶部菜单 **Dialplan → Dialplan Manager**（或 **Dialplan → Inbound Routes**）。
2. 点 **+** 新增，填：
   - **Name**：`ForgeAgent_CC_Test`
   - **Domain**：选你的 domain
   - **Context**：保持默认 `public`
   - **Condition**（条件）加一条：
     - Type = `destination`，Field = `destination_number`，Expression = `^999999$`（测试拨 999999）
3. 在 **Actions**（动作）里按顺序加：
   - 第 1 条：**Action = `transfer`，Data = `888888 XML`**（转到你建的队列分机）
   - 若想先放提示音再加：**Action = `playback`，Data = `ivr/ivr-welcome_to_freeswitch.wav`**，然后 transfer
4. **Save**，然后右上角点 **Reload**（或 **Advanced → Dialplan → Reload XML**，让配置生效）。
5. 验证：用 021800002 话机拨 `999999`，应听到振铃、021800001 坐席话机响铃；接通后双向通话正常。

> 说明：P1 阶段只要你拨通、有 `CHANNEL_CREATE/ANSWER/HANGUP_COMPLETE` 事件即可；ASR/IVR 是后续 P2/P3。

### 3.5 建队列入场规则（P2 转人工必需）

> 为什么需要：`mod_callcenter` 的队列不是拨号码就能进，必须在 dialplan 里有一个号码执行 `callcenter` 应用。P2 的转人工下发的是 `uuid_transfer <uuid> 888888 XML default`，所以 `888888` 这条规则必须有，否则转人工会失败。

1. **Dialplan → Dialplan Manager → +**，填：
   - **Name**：`CC_Queue_Entry`
   - **Domain**：选你的 domain
   - **Context**：`default`（必须与后端 `agent.callcenter.freeswitch.dialplan-context` 一致）
   - **Condition**：Type = `destination`，Field = `destination_number`，Expression = `^888888$`
2. **Actions**（按顺序加两条）：
   - 第 1 条：**Action = `answer`**，Data 留空
   - 第 2 条：**Action = `callcenter`**，**Data = `after_sales@default`**（`队列名@domain`）
3. **Save** → **Reload XML**。
4. 验证（不需要打电话）：
   ```bash
   fs_cli -x "callcenter_config queue list"
   ```
   应看到 `after_sales@default`。

> 若坐席没签入，转进来会一直排队放等待音乐，这是正常的；先按规定用 `*22` 签入坐席再测。

---

## 待办 4：自定义事件发射（P2/P3 才需要，P1 可跳过）

### 先判断你现在需不需要

- **P1 联调（事件面）**：**不需要**。标准事件 `CHANNEL_CREATE / CHANNEL_ANSWER / CHANNEL_HANGUP_COMPLETE / DTMF` 由 FreeSWITCH 自动发，starter 订阅即有。
- **P2/P3（ASR 转写、转人工时间轴、录音就绪、话单）**：才需要发下面的 `forgeagent::*` 自定义事件。

### 4.1 在云主机放一个 Lua 脚本（SSH 命令行，非页面）

FusionPBX 页面本身不提供"发自定义事件"的按钮，必须用 Lua 脚本 + dialplan 的 `lua` app。

SSH 登录云主机，创建脚本：

```bash
sudo tee /usr/share/freeswitch/scripts/forgeagent_event.lua > /dev/null <<'LUA'
-- 用法：在 dialplan 里 lua forgeagent_event.lua <子类型>
-- 子类型：asr_result / transfer / record_ready / cdr / agent_state
local subtype = argv[1] or ""
local uuid    = session:getVariable("uuid") or ""
local subclass = "forgeagent::" .. subtype
if subtype == "agent_state" then subclass = "callcenter::agent_state" end

local e = freeswitch.Event("CUSTOM", subclass)
e:addHeader("Unique-ID", uuid)

if subtype == "asr_result" then
  e:addHeader("speaker", "caller")
  e:addHeader("text", "我要查一下订单状态")
  e:addHeader("confidence", "0.93")   -- 注意:当前业务层只认 Number,字符串会丢置信度(后续P3修)
  e:addHeader("isFinal", "true")
  e:addHeader("startMs", "1800")
  e:addHeader("endMs", "3200")
elseif subtype == "transfer" then
  e:addHeader("variable_transferFrom", "bot")
  e:addHeader("CC-Queue", "after_sales")
elseif subtype == "record_ready" then
  e:addHeader("variable_recordRecordingId", "rec-" .. uuid)
  e:addHeader("variable_recordFilePath", "/var/lib/freeswitch/recordings/" .. uuid .. ".wav")
elseif subtype == "cdr" then
  e:addHeader("durationSec", "96")
  e:addHeader("billSec", "96")
  e:addHeader("hangupCause", "NORMAL_CLEARING")
  e:addHeader("hangupDisposition", "NORMAL")
elseif subtype == "agent_state" then
  e:addHeader("CC-Agent", "021800001")
  e:addHeader("CC-Agent-State", "READY")
end
e:fire()
LUA
```

### 4.2 在页面把脚本挂到 dialplan

1. **Dialplan → Dialplan Manager**，编辑 `ForgeAgent_CC_Test`。
2. 在 Actions 里加一条（放在 transfer 之前）：
   - **Action = `lua`**
   - **Data = `forgeagent_event.lua asr_result`**
3. **Save** + **Reload XML**。
4. 拨测时,ForgeAgent 日志应出现 `[CC-ESL] 事件已处理 name=forgeagent::asr_result`,且 `agent_cc_call_segment` 多一条记录。

### 4.3 验证事件真的到了 Java

在云主机抓 ESL 事件确认发射成功：

```bash
fs_cli -x '/events plain CUSTOM'
# 另一个窗口拨测,应看到 Event-Subclass: forgeagent::asr_result
```

再看 ForgeAgent 后端日志（应用日志路径按你的部署）：

```bash
grep "CC-ESL" /path/to/forgeagent.log | tail -20
```

出现 `事件已处理` 且 `count>=1` 即全链路打通。

---

## 联调顺序小结

1. 待办 2（渠道记录）→ 2. 待办 3.2（分机）+ 3.3（队列）+ 3.4（拨号计划）→ 3. 云主机启动后端、注入 `FS_ESL_PASSWORD` → 4. 分机拨 999999 → 5. 看 `agent_cc_call` 是否生成 RINGING→ANSWERED→ENDED 轨迹。
6. P1 通了再做待办 4（自定义事件），进入 P2/P3。
