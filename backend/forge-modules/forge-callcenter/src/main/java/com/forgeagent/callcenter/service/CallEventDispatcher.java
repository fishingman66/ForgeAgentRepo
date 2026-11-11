package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.common.CsEventEnvelope;
import com.forgeagent.callcenter.common.CsEventTypes;
import com.forgeagent.callcenter.common.CsPiiMasker;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallChannelConfig;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.domain.CsCallStatus;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import com.forgeagent.callcenter.gateway.CsVoiceGateway;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import com.forgeagent.customerservice.service.SeatAssistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音回调编排器(V1~V2):回调处理的总入口,把一次 AICC 回调拆成
 * 「防护 → 归一化 → 逐事件幂等 → 事件分派 → 状态推进 → 业务绑定 → 弹屏下行」七个阶段。
 *
 * <p>职责边界:本类只做编排与时序,不承载具体业务——
 * 转写入库、录音入库、话单对账、坐席状态、业务绑定各归其 Service;
 * 新增事件类型时只需要三步:供应商映射表加映射(VoiceProvider)、
 * 状态机加目标态(CallStateMachine.targetOf)、新增一个 applyXxx 分派方法,其余阶段零改动。</p>
 *
 * <p>响应纪律:对外固定 AICC 格式 {@code {message, retcode}} 且 HTTP 200,
 * 任何内部异常都不外抛(全局异常处理器会包装成项目 R&lt;T&gt;,语音侧不适用);
 * 对外的失败信息保持模糊(不区分签名错 / 渠道错),细节只进内部日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallEventDispatcher {

    /** AICC 约定的成功响应:retcode 必须 0,HTTP 200。 */
    public static final Map<String, String> SUCCESS = Map.of("message", "success", "retcode", "0");
    /** 统一拒绝码:签名 / 时间窗 / nonce / 渠道 / 报文五类失败共用,避免给探测方提供区分度。 */
    public static final Map<String, String> REJECTED = Map.of("message", "invalid signature", "retcode", "1001");

    private final CsVoiceCallbackGuard guard;
    private final CsVoiceGateway gateway;
    private final CallIdempotencyService idempotency;
    private final CallStateMachine stateMachine;
    private final TranscriptIngestService transcriptIngest;
    private final RecordingIngestService recordingIngest;
    private final CdrReconciler cdrReconciler;
    private final CallSeatStateService seatStateService;
    private final CallBusinessBinder businessBinder;
    private final CsCallMapper callMapper;
    private final CallcenterProperties properties;
    /** SSE 通道属文本域 Bean,客服开关关闭时为空,弹屏挂点静默降级。 */
    private final ObjectProvider<CsRealtimeTransport> transportProvider;
    /** A2 坐席推荐服务同属文本域,关闭时为空,推荐挂点静默跳过。 */
    private final ObjectProvider<SeatAssistService> seatAssistProvider;

    /** 回调总入口;五个失败点全部收敛到 REJECTED,成功路径永远返回 SUCCESS。 */
    public Map<String, String> dispatch(String provider, String channelCode, String timestamp, String nonce,
                                        String signature, String rawBody) {
        CsCallChannelConfig channel;
        try {
            channel = guard.require(channelCode);
        } catch (Exception e) {
            log.warn("[CC] 回调渠道不可用 code={}: {}", channelCode, e.getMessage());
            return REJECTED;
        }
        if (!guard.withinTolerance(timestamp)) {
            return REJECTED;
        }
        try {
            guard.checkNonce(channelCode, nonce);
        } catch (Exception e) {
            return REJECTED;
        }
        String secret = guard.resolveSecret(channel);
        if (!gateway.verify(secret, timestamp, nonce, rawBody, signature)) {
            // 内部排障日志:只记哈希与长度,不落明文密钥与签名
            log.warn("[CC] 回调验签失败 code={} ts={} bodySha={} bodyLen={} sigLen={} secretLen={}",
                channelCode, timestamp, sha256Prefix(rawBody),
                rawBody == null ? -1 : rawBody.length(),
                signature == null ? -1 : signature.length(),
                secret == null ? -1 : secret.length());
            return REJECTED;
        }
        List<CsVoiceEvent> events;
        try {
            events = gateway.parse(provider, channelCode, rawBody);
        } catch (Exception e) {
            log.warn("[CC] 回调报文解析失败 code={}: {}", channelCode, e.getMessage());
            return REJECTED;
        }
        for (CsVoiceEvent event : events) {
            try {
                handle(channel, event);
            } catch (Exception e) {
                log.error("[CC] 事件处理失败 callId={} type={}", event.callId(), event.type(), e);
                deadLetter(event, e.getMessage());
            }
        }
        return SUCCESS;
    }

    /**
     * 单事件处理骨架:查/建通话 → 事件轨迹幂等 → 状态单调推进 → 事件分派 → 业务挂机钩子 → 弹屏。
     * 重复事件在 tryRecord 处短路;callId 缺失直接忽略(V0 契约要求所有事件带 callId)。
     */
    private void handle(CsCallChannelConfig channel, CsVoiceEvent event) {
        String callId = event.callId();
        if (callId.isBlank()) {
            log.warn("[CC] 事件缺少 callId,忽略 type={}", event.type());
            return;
        }
        Date now = new Date();
        CsCall call = idempotency.findCall(channel.getMediaProvider(), callId);
        if (call == null) {
            call = newCall(channel, event, now);
            try {
                callMapper.insert(call);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首呼:另一请求已建通话,回查后继续处理(事件轨迹唯一键兜底幂等)
                call = idempotency.findCall(channel.getMediaProvider(), callId);
                if (call == null) {
                    throw e;
                }
            }
        }

        String from = call.getStatus();
        String target = stateMachine.targetOf(event);
        String to = CsCallStatus.advance(from, target);
        String payload = fingerprint(event) + " " + event.rawType();
        if (!idempotency.tryRecord(call, new CallIdempotencyService.CsVoiceEventView(
                event.eventGenTime(), event.type(), payload), from, to)) {
            return;
        }
        applyEvent(call, event, channel, now);

        call.setStatus(to);
        call.setUpdateTime(now);
        callMapper.updateById(call);
        // 挂机结案钩子:状态实际推进到 ENDED 才触发;迟到的 CDR 不重复触发(否则质检/指标重复)
        if ("ENDED".equals(to) && !"ENDED".equals(from)) {
            businessBinder.onHangup(call);
        }
        pushControlPlane(call, event);
    }

    /** 事件分派:每个归一化事件的副作用集中在一个方法,方法名即事件语义。 */
    private void applyEvent(CsCall call, CsVoiceEvent event, CsCallChannelConfig channel, Date now) {
        switch (event.type()) {
            // 呼入振铃:登记主被叫(号码统一 PII 脱敏后落库)并绑定客服会话(V2)
            case "call.ring" -> applyRing(call, event, now);
            // 接听:记录 answer_at,是接通率统计的口径字段
            case "call.answer" -> call.setAnswerAt(now);
            // ASR 最终转写:写转写分片,后续喂质检与知识缺口;A2 起定稿分片同步触发坐席推荐
            case "call.asr_result" -> {
                CsCallSegment segment = transcriptIngest.ingest(call, event);
                triggerSeatAssist(call, segment);
            }
            // 机器人话术:同样写转写分片,并标记通话经过机器人(bot_resolve_rate 口径)
            case "call.bot_utterance" -> {
                transcriptIngest.ingest(call, event);
                call.setBotHandled("1");
            }
            // 按键:追加 dtmf_digits,IVR 菜单轨迹(扩展点:IVR 节点停留时长分析)
            case "call.dtmf" -> appendDtmf(call, event.str("digits"));
            // 转人工:回填技能组轨迹并建工单入 ACD 队列(V2)
            case "call.transfer" -> applyTransfer(call, event);
            // 坐席状态:更新 agent_cc_call_seat_state;READY 顺带触发一次 ACD 分配(V2)
            case "call.seat_state" -> applySeatState(call, event, channel);
            // 录音就绪:取回一次入存储,对象键与留存时间落库(D6)
            case "call.record.ready" -> recordingIngest.ingest(call, event);
            // 挂机:时长与挂机原因,结案钩子在状态推进后统一触发
            case "call.hangup" -> cdrReconciler.applyHangup(call, event, now);
            // 话单:迟到 CDR 对账,只补字段不回退状态
            case "call.cdr" -> cdrReconciler.reconcile(call, event);
            default -> log.debug("[CC] 未分派事件 {}", event.type());
        }
    }

    private void applyRing(CsCall call, CsVoiceEvent event, Date now) {
        call.setRingAt(now);
        call.setCallerNumber(CsPiiMasker.mask(event.str("caller")));
        call.setCalleeNumber(event.str("callee"));
        call.setAni(CsPiiMasker.mask(event.str("ani")));
        call.setDnis(event.str("dnis"));
        String direction = event.str("direction");
        call.setDirection(direction == null || direction.isBlank() ? "inbound" : direction);
        businessBinder.onRing(call, event);
    }

    private void applyTransfer(CsCall call, CsVoiceEvent event) {
        call.setTransferFrom(event.str("transferFrom"));
        call.setTransferTo(event.str("transferTo"));
        call.setWorkNo(event.workNo());
        businessBinder.onTransfer(call, event);
    }

    private void applySeatState(CsCall call, CsVoiceEvent event, CsCallChannelConfig channel) {
        seatStateService.apply(channel.getWorkspaceId(), event);
        if (event.workNo() != null && !event.workNo().isBlank()) {
            call.setWorkNo(event.workNo());
        }
        businessBinder.onSeatState(call, event);
    }

    /** 首通创建:状态从 RINGING 起步;ring 之外的乱序首事件(如 CDR 先到)同样兜底建通,字段由后续事件补全。 */
    private CsCall newCall(CsCallChannelConfig channel, CsVoiceEvent event, Date now) {
        CsCall call = new CsCall();
        call.setCallId(event.callId());
        call.setMediaProvider(channel.getMediaProvider());
        call.setChannelCode(channel.getChannelCode());
        call.setWorkspaceId(channel.getWorkspaceId());
        call.setAgentId(channel.getAgentId());
        call.setDirection("inbound");
        call.setStatus(CsCallStatus.RINGING.name());
        call.setBotHandled("0");
        call.setCreateTime(now);
        return call;
    }

    /** 按键序列追加:IVR 多级菜单的 digits 逐次到达,按序拼接即完整按键轨迹。 */
    private void appendDtmf(CsCall call, String digits) {
        if (digits == null || digits.isBlank()) {
            return;
        }
        String existing = call.getDtmfDigits();
        call.setDtmfDigits(existing == null || existing.isBlank() ? digits : (existing + digits));
    }

    /** V0 契约指纹,见 {@link CallFingerprints}。 */
    public String fingerprint(CsVoiceEvent event) {
        return CallFingerprints.of(event.callId(), event.rawType(), event.eventGenTime(), event.content());
    }

    /**
     * A2:定稿分片进坐席辅助推荐。
     *
     * <p>判定(is_final / 说话人 / 置信度 / 会话绑定 / 节流)全部收在推荐服务内,
     * 本方法只做上下文搬运;推荐服务本身是 @Async 旁路,回调线程不被检索耗时拖住。
     * 客服开关关闭时 Bean 缺失,静默跳过。</p>
     */
    private void triggerSeatAssist(CsCall call, CsCallSegment segment) {
        if (segment == null) {
            return;
        }
        SeatAssistService assist = seatAssistProvider.getIfAvailable();
        if (assist == null) {
            return;
        }
        assist.suggestForVoice(new SeatAssistService.VoiceAssistInput(
            call.getId(), call.getChannelCode(), call.getWorkspaceId(), call.getConversationId(),
            call.getTicketId(), segment.getSpeaker(), segment.getText(),
            segment.getAsrConfidence() == null ? null : segment.getAsrConfidence().doubleValue(),
            "1".equals(segment.getIsFinal())));
    }

    /**
     * 控制面下行挂点(V1 落点、V2 起生效):把 CALL_* 事件投给 SSE 通道坐席弹屏。
     * 扩展点:语音坐席工作台实时刷新、外呼进度推送都走同一条通道,
     * 只需在这里补事件映射;conversation_id 为空时(V2 会话绑定前)静默跳过。
     */
    private void pushControlPlane(CsCall call, CsVoiceEvent event) {
        if (!Boolean.TRUE.equals(properties.getTranscriptPushEnabled()) || call.getConversationId() == null) {
            return;
        }
        CsRealtimeTransport transport = transportProvider.getIfAvailable();
        if (transport == null) {
            return;
        }
        String type = switch (event.type()) {
            case "call.ring" -> CsEventTypes.CALL_RING;
            case "call.answer" -> CsEventTypes.CALL_ANSWER;
            case "call.hangup" -> CsEventTypes.CALL_HANGUP;
            case "call.dtmf" -> CsEventTypes.CALL_DTMF;
            case "call.asr_result", "call.bot_utterance" -> CsEventTypes.CALL_TRANSCRIPT;
            case "call.record.ready" -> CsEventTypes.CALL_RECORD_READY;
            case "call.transfer" -> CsEventTypes.CALL_TRANSFER;
            default -> null;
        };
        if (type == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", call.getCallId());
        payload.put("callStatus", call.getStatus());
        if (CsEventTypes.CALL_TRANSCRIPT.equals(type)) {
            // A2 转写上屏:payload 只增字段;正文沿用入库前的同一份 PII 脱敏结果,不回推明文
            payload.put("speaker", event.str("speaker"));
            payload.put("text", CsPiiMasker.mask(event.str("text")));
            payload.put("isFinal", "true".equalsIgnoreCase(String.valueOf(event.content().get("isFinal"))));
        }
        transport.push(CsEventEnvelope.of(type, call.getConversationId(), call.getTicketId(), payload));
    }

    /**
     * 死信:call 已存在时按 dead.* 类型追加事件轨迹(payload 存错误摘要),供人工复核;
     * call 未建时只记日志(V1 边界)。死信不改变对外响应,避免 AICC 重试风暴。
     */
    private void deadLetter(CsVoiceEvent event, String error) {
        try {
            if (event.callId().isBlank()) {
                return;
            }
            CsCall call = idempotency.findCall(event.provider(), event.callId());
            if (call == null) {
                return;
            }
            CallIdempotencyService.CsVoiceEventView view = new CallIdempotencyService.CsVoiceEventView(
                event.eventGenTime(), "dead." + event.type(), truncate(error));
            idempotency.tryRecord(call, view, call.getStatus(), null);
        } catch (Exception e) {
            log.warn("[CC] 死信记录失败 callId={}: {}", event.callId(), e.getMessage());
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1900 ? value : value.substring(0, 1900);
    }

    /** 验签排障用的 body 摘要:只给哈希前缀,不落明文。 */
    private static String sha256Prefix(String value) {
        if (value == null) {
            return "null";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return "err";
        }
    }
}
