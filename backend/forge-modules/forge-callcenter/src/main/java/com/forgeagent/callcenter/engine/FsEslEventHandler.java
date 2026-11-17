package com.forgeagent.callcenter.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.gateway.VoiceProvider;
import com.forgeagent.callcenter.service.CallEventDispatcher;
import link.thingscloud.freeswitch.esl.spring.boot.starter.annotation.EslEventName;
import link.thingscloud.freeswitch.esl.spring.boot.starter.handler.EslEventHandler;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FreeSWITCH ESL 事件接入(C1-real):把 starter 推送的 EslEvent 转成业务层
 * 既有的 ESL 风格报文(events 数组信封),交给 {@link CallEventDispatcher#dispatchTrusted}
 * 走与 HTTP 回调完全相同的归一化 + 幂等 + 状态机编排。
 *
 * <p>事件路由:starter 的 IEslEventListenerTemplate 按 {@code EslEvent.getEventName()}
 * 精确匹配本类注解。标准事件(CHANNEL_CREATE 等/DTMF)直接用事件名;CUSTOM 事件 getEventName()
 * 返回 "CUSTOM",其子类名在 header {@code Event-Subclass}(如 forgeagent::asr_result),
 * 本类按子类值还原成归一化器期望的 eslEventName。</p>
 *
 * <p>安全:ESL 是内网长连接、建连时已由 starter 用密码认证,故走 dispatchTrusted 跳过
 * HTTP 回调的 HMAC/时间窗/nonce 三关;渠道可用性由 dispatcher 校验。</p>
 */
@Slf4j
@Component
@ConditionalOnCallcenterEnabled
@RequiredArgsConstructor
@EslEventName({"CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP_COMPLETE", "DTMF", "CUSTOM"})
public class FsEslEventHandler implements EslEventHandler {

    private static final String HEADER_UNIQUE_ID = "Unique-ID";
    private static final String HEADER_SUBCLASS = "Event-Subclass";
    private static final String HEADER_WORK_NO = "variable_workNo";
    private static final String HEADER_CC_AGENT = "CC-Agent";

    private final CallEventDispatcher dispatcher;
    private final CallcenterProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(String address, EslEvent event) {
        try {
            Map<String, String> headers = event.getEventHeaders();
            if (headers == null || headers.isEmpty()) {
                log.debug("[CC-ESL] 空事件头,忽略 name={} addr={}", event.getEventName(), address);
                return;
            }
            String eslName = resolveEslName(event, headers);
            String channelCode = properties.getFreeswitch().getChannelCode();
            String workNo = headers.getOrDefault(HEADER_WORK_NO, headers.getOrDefault(HEADER_CC_AGENT, ""));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eslEventName", eslName);
            item.put("eventGenTimeMs", toMillis(event, headers));
            item.put("workNo", workNo == null ? "" : workNo);
            item.put("headers", new LinkedHashMap<>(headers));

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("events", new Object[]{item});
            String rawBody = mapper.writeValueAsString(envelope);

            int count = dispatcher.dispatchTrusted(VoiceProvider.FS.code(), channelCode, rawBody);
            if (count < 0) {
                log.warn("[CC-ESL] 事件未消费 name={} addr={} callId={}",
                    eslName, address, headers.get(HEADER_UNIQUE_ID));
            } else {
                log.debug("[CC-ESL] 事件已处理 name={} addr={} callId={} count={}",
                    eslName, address, headers.get(HEADER_UNIQUE_ID), count);
            }
        } catch (Exception e) {
            // 单事件异常不外抛:starter 的事件分发线程不能被一条坏事件打挂
            log.error("[CC-ESL] 事件处理失败 addr={} name={}", address,
                event == null ? "null" : event.getEventName(), e);
        }
    }

    /** CUSTOM 事件用 Event-Subclass 还原子类名;标准事件用 getEventName。 */
    static String resolveEslName(EslEvent event, Map<String, String> headers) {
        String subclass = headers.get(HEADER_SUBCLASS);
        if (subclass != null && !subclass.isBlank()) {
            return subclass;
        }
        return event.getEventName();
    }

    /**
     * 事件时间(ms):优先用 EslEvent.getEventDateTimestamp()。
     * ESL 的 Event-Date-Timestamp 通常是微秒,但本库与 fixture 契约按秒处理
     * (FsEventNormalizer.eventGenTimeMs 会把 <1e10 的数值乘 1000),
     * 因此这里直接用 header 原值交给归一化器,避免二次换算。
     */
    static long toMillis(EslEvent event, Map<String, String> headers) {
        String raw = headers.get("Event-Date-Timestamp");
        if (raw != null && !raw.isBlank()) {
            try {
                long value = Long.parseLong(raw);
                return value < 10_000_000_000L ? value * 1000 : value;
            } catch (NumberFormatException ignored) {
                // 落到下面的 getEventDateTimestamp 兜底
            }
        }
        long ts = event.getEventDateTimestamp();
        return ts < 10_000_000_000L ? ts * 1000 : ts;
    }
}
