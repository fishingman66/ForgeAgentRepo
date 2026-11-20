package com.forgeagent.callcenter.common;

/**
 * 渠道类型取值(V0.5)。已交付取值:widget/h5/wechat_mp/wecom/openapi/email;
 * VOICE_INBOUND 为 V1 预留。
 *
 * <p>扩展方向:外呼回访(V1 后)、视频客服、小程序客服加枚举值;
 * 新渠道接入 = 文本域加 ChannelAdapter 或语音域加 CsVoiceGateway 实现,取值约束在本类收口。</p>
 */
public enum CsChannelType {

    WIDGET("widget"),
    H5("h5"),
    WECHAT_MP("wechat_mp"),
    WECOM("wecom"),
    OPENAPI("openapi"),
    /** P3/B5:邮件工单渠道,走 IMAP 轮询而非 HTTP 回调 */
    EMAIL("email"),
    VOICE_INBOUND("voice_inbound");

    private final String code;

    CsChannelType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 未知取值返回 null,由调用方决定兜底;大小写不敏感。 */
    public static CsChannelType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (CsChannelType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
