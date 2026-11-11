package com.forgeagent.customerservice.channel;

/** 渠道上行消息(C3),msgId 直接作为 clientMsgId 复用幂等链路。 */
public record CsInboundMessage(String msgId, String externalUserId, String content,
                               String msgType, String toUser) {
}
