package com.forgeagent.callcenter.gateway;

import java.util.Map;

/**
 * 归一化语音事件(V1):业务层只认本结构,厂商字段差异全部隔离在 CsVoiceGateway 实现里。
 *
 * @param provider     供应商标识
 * @param channelCode  语音渠道编码
 * @param rawType      厂商原始 eventType(幂等指纹与轨迹记录用)
 * @param type         归一化事件名,与 V0 sequence.json 一致(call.ring / call.answer / ...)
 * @param workNo       坐席工号,可能为空
 * @param eventGenTime 供应商事件产生时间(ms),作为 event_seq 做乱序与幂等判定
 * @param content      厂商 content 原样保留,字段访问用 {@link #str(String)}
 */
public record CsVoiceEvent(String provider, String channelCode, String rawType, String type,
                           String workNo, long eventGenTime, Map<String, Object> content) {

    public CsVoiceEvent {
        content = content == null ? Map.of() : content;
    }

    /** 呼叫标识,缺失时为空串,由调度层判死信。 */
    public String callId() {
        return str("callId") == null ? "" : str("callId");
    }

    public String str(String key) {
        Object value = content.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public Long longValue(String key) {
        Object value = content.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
