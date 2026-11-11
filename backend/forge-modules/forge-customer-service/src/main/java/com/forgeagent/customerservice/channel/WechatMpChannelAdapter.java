package com.forgeagent.customerservice.channel;

import com.forgeagent.callcenter.common.CsChannelSignature;

import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 微信公众号适配器(C3):明文 XML 消息,验签 + 时间窗 + MsgId 幂等。 */
@Component
public class WechatMpChannelAdapter implements CsChannelAdapter {

    @Override
    public String type() {
        return "wechat_mp";
    }

    @Override
    public boolean verify(CsChannel channel, Map<String, String> params, CsModuleProperties properties) {
        String signature = params.get("signature");
        String timestamp = params.get("timestamp");
        String nonce = params.get("nonce");
        String secret = channel.getChannelSecret();
        if (signature == null || timestamp == null || nonce == null || secret == null || secret.isBlank()) {
            return false;
        }
        if (!CsChannelSignature.withinTolerance(timestamp, properties.getChannelCallbackToleranceSeconds())) {
            return false;
        }
        String expected = CsChannelSignature.sha1(secret, timestamp, nonce);
        return CsChannelSignature.constantTimeEquals(expected, signature);
    }

    @Override
    public CsInboundMessage parse(String body, Map<String, String> params) {
        String msgType = tag(body, "MsgType");
        String from = tag(body, "FromUserName");
        String to = tag(body, "ToUserName");
        String content = "text".equalsIgnoreCase(msgType) ? tag(body, "Content") : null;
        String msgId = tag(body, "MsgId");
        if (msgId == null || msgId.isBlank()) {
            msgId = params.get("timestamp") + "-" + tag(body, "CreateTime");
        }
        return new CsInboundMessage(msgId, from, content, msgType, to);
    }

    @Override
    public String formatReply(CsInboundMessage inbound, String reply) {
        long now = System.currentTimeMillis() / 1000;
        return "<xml>"
            + "<ToUserName>" + cdata(inbound.externalUserId()) + "</ToUserName>"
            + "<FromUserName>" + cdata(inbound.toUser()) + "</FromUserName>"
            + "<CreateTime>" + now + "</CreateTime>"
            + "<MsgType>" + cdata("text") + "</MsgType>"
            + "<Content>" + cdata(reply) + "</Content>"
            + "</xml>";
    }

    @Override
    public String echo(Map<String, String> params) {
        return params.get("echostr");
    }

    /** 读取 XML 标签内容,兼容 CDATA。 */
    public static String tag(String xml, String name) {
        if (xml == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
            return value.substring(9, value.length() - 3);
        }
        return value;
    }

    /** CDATA 转义,避免回复内容里的 ]]> 截断报文。 */
    public static String cdata(String text) {
        String safe = text == null ? "" : text.replace("]]>", "]]]]><![CDATA[>");
        return "<![CDATA[" + safe + "]]>";
    }
}
