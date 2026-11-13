package com.forgeagent.callcenter.gateway;

import java.util.Map;

/**
 * 供应商标识(V1)。AICC_MOCK 只用于 dev/test,prod 由就绪校验器拒绝;
 * 接入真实厂商时新增枚举并实现 CsVoiceGateway,业务层零改动。
 */
public enum VoiceProvider {

    AICC("AICC"),
    AICC_MOCK("AICC_MOCK"),
    /** 自建 FreeSWITCH 主路线(V-1,C1-real 起真实接入)。 */
    FS("FS"),
    /** FS 的本地开发 Mock(V-5,C1 交付):仅 dev/test,prod 由就绪校验器拒绝。 */
    FS_MOCK("FS_MOCK");

    private final String code;

    VoiceProvider(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 未知取值返回 null,由调用方决定拒绝;大小写不敏感。 */
    public static VoiceProvider fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (VoiceProvider provider : values()) {
            if (provider.code.equalsIgnoreCase(code)) {
                return provider;
            }
        }
        return null;
    }

    /** 厂商 eventType -> 归一化事件,与 V0 sequence.json 的 eventTypeMap 一致。 */
    public static final Map<String, String> AICC_EVENT_TYPE_MAP = Map.ofEntries(
        Map.entry("callRing", "call.ring"),
        Map.entry("callAnswer", "call.answer"),
        Map.entry("asrResult", "call.asr_result"),
        Map.entry("botUtterance", "call.bot_utterance"),
        Map.entry("dtmf", "call.dtmf"),
        Map.entry("transfer", "call.transfer"),
        Map.entry("seatState", "call.seat_state"),
        Map.entry("recordReady", "call.record.ready"),
        Map.entry("hangup", "call.hangup"),
        Map.entry("cdr", "call.cdr"));
}
