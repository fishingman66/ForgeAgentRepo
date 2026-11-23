package com.forgeagent.customerservice.channel;

/** 渠道上行消息(C3),msgId 直接作为 clientMsgId 复用幂等链路。 */
public record CsInboundMessage(String msgId, String externalUserId, String content,
                               String msgType, String toUser, String rawMessageId) {
    /** 非邮件渠道无原始 Message-ID,保留旧 5 参构造兼容。 */
    public CsInboundMessage(String msgId, String externalUserId, String content,
                            String msgType, String toUser) {
        this(msgId, externalUserId, content, msgType, toUser, null);
    }
}
