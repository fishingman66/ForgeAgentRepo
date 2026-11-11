package com.forgeagent.customerservice.channel;

import com.forgeagent.callcenter.common.CsChannelSignature;

import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 企业微信适配器(C3):按明文模式接入,验签参数名与公众号不同。
 *
 * <p>加密模式(AES-CBC 解密 echostr / 消息体)需要 EncodingAESKey 与 corpId 配置,
 * 本轮按方案只覆盖验签、时间窗、nonce 与消息幂等;加密模式留作后续专项。</p>
 */
@Component
public class WecomChannelAdapter implements CsChannelAdapter {

    @Override
    public String type() {
        return "wecom";
    }

    @Override
    public boolean verify(CsChannel channel, Map<String, String> params, CsModuleProperties properties) {
        String signature = params.getOrDefault("msg_signature", params.get("signature"));
        String timestamp = params.get("timestamp");
        String nonce = params.get("nonce");
        String echo = params.get("echostr");
        String secret = channel.getChannelSecret();
        if (signature == null || timestamp == null || nonce == null || secret == null || secret.isBlank()) {
            return false;
        }
        if (!CsChannelSignature.withinTolerance(timestamp, properties.getChannelCallbackToleranceSeconds())) {
            return false;
        }
        String expected = echo == null
            ? CsChannelSignature.sha1(secret, timestamp, nonce)
            : CsChannelSignature.sha1(secret, timestamp, nonce, echo);
        return CsChannelSignature.constantTimeEquals(expected, signature);
    }

    @Override
    public CsInboundMessage parse(String body, Map<String, String> params) {
        String msgType = WechatMpChannelAdapter.tag(body, "MsgType");
        String from = WechatMpChannelAdapter.tag(body, "FromUserName");
        if (from == null || from.isBlank()) {
            from = params.get("openid");
        }
        String to = WechatMpChannelAdapter.tag(body, "ToUserName");
        String content = "text".equalsIgnoreCase(msgType)
            ? WechatMpChannelAdapter.tag(body, "Content") : null;
        String msgId = WechatMpChannelAdapter.tag(body, "MsgId");
        if (msgId == null || msgId.isBlank()) {
            msgId = "wecom-" + params.get("timestamp") + "-" + WechatMpChannelAdapter.tag(body, "CreateTime");
        }
        return new CsInboundMessage(msgId, from, content, msgType, to);
    }

    @Override
    public String formatReply(CsInboundMessage inbound, String reply) {
        long now = System.currentTimeMillis() / 1000;
        return "<xml>"
            + "<ToUserName>" + WechatMpChannelAdapter.cdata(inbound.externalUserId()) + "</ToUserName>"
            + "<FromUserName>" + WechatMpChannelAdapter.cdata(inbound.toUser()) + "</FromUserName>"
            + "<CreateTime>" + now + "</CreateTime>"
            + "<MsgType>" + WechatMpChannelAdapter.cdata("text") + "</MsgType>"
            + "<Content>" + WechatMpChannelAdapter.cdata(reply) + "</Content>"
            + "</xml>";
    }

    @Override
    public String echo(Map<String, String> params) {
        return params.get("echostr");
    }
}
