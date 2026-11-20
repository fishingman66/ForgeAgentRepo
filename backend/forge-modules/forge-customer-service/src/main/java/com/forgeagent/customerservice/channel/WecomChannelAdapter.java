package com.forgeagent.customerservice.channel;

import com.forgeagent.callcenter.common.CsChannelSignature;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;

/**
 * 企业微信适配器(C3 + P3/B5):明文模式与<b>安全模式(加密)</b>双支持。
 *
 * <p>模式判定:<b>渠道配了 EncodingAESKey 即进入加密模式</b>(强制),
 * 解密/验签失败一律拒绝,<b>绝不回退明文</b>——否则攻击者只要发一条明文报文就能绕过加密。</p>
 *
 * <p>复用的配置口径:</p>
 * <ul>
 *   <li>{@code channel_secret} = 回调 Token(两种模式共用);</li>
 *   <li>{@code encoding_aes_key} = 43 位 EncodingAESKey(加密存储,仅此处解密使用);</li>
 *   <li>{@code corp_id} = 企微 CorpID,用于 receiveid 校验(防跨企业报文重放)。</li>
 * </ul>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class WecomChannelAdapter implements CsChannelAdapter {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String type() {
        return "wecom";
    }

    // ---------------- 验签 ----------------

    @Override
    public boolean verify(CsChannel channel, Map<String, String> params, CsModuleProperties properties) {
        return verify(channel, params, null, properties);
    }

    /**
     * 验签。加密模式下 POST 报文的前像含 {@code <Encrypt>},必须从 body 取;
     * GET 验证两者的前像都是 echostr(明文串或密文),公式一致。
     */
    @Override
    public boolean verify(CsChannel channel, Map<String, String> params, String body,
                          CsModuleProperties properties) {
        String signature = params.getOrDefault("msg_signature", params.get("signature"));
        String timestamp = params.get("timestamp");
        String nonce = params.get("nonce");
        String secret = channel.getChannelSecret();
        if (signature == null || timestamp == null || nonce == null || secret == null || secret.isBlank()) {
            return false;
        }
        if (!CsChannelSignature.withinTolerance(timestamp, properties.getChannelCallbackToleranceSeconds())) {
            return false;
        }
        String extra = params.get("echostr");
        if ((extra == null || extra.isBlank()) && encryptedMode(channel)) {
            extra = WechatMpChannelAdapter.tag(body, "Encrypt");
            if (extra == null || extra.isBlank()) {
                // 配了 AESKey 却收到无 Encrypt 的报文:直接拒绝,不接受明文降级
                log.warn("[CS] 企微加密模式收到缺少 Encrypt 的报文,已拒绝");
                return false;
            }
        }
        String expected = (extra == null || extra.isBlank())
            ? CsChannelSignature.sha1(secret, timestamp, nonce)
            : CsChannelSignature.sha1(secret, timestamp, nonce, extra);
        return CsChannelSignature.constantTimeEquals(expected, signature);
    }

    // ---------------- 解析 ----------------

    @Override
    public CsInboundMessage parse(String body, Map<String, String> params) {
        return parsePlain(body, params);
    }

    @Override
    public CsInboundMessage parse(String body, Map<String, String> params, CsChannel channel) {
        if (!encryptedMode(channel)) {
            return parsePlain(body, params);
        }
        String encrypt = WechatMpChannelAdapter.tag(body, "Encrypt");
        if (encrypt == null || encrypt.isBlank()) {
            throw new ServiceException("企微加密模式报文缺少 Encrypt 节点");
        }
        String plain = WecomCrypto.decrypt(channel.getEncodingAesKey(), channel.getCorpId(), encrypt);
        return parsePlain(plain, params);
    }

    private CsInboundMessage parsePlain(String body, Map<String, String> params) {
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

    // ---------------- 回包 ----------------

    @Override
    public String formatReply(CsInboundMessage inbound, String reply) {
        return plainReplyXml(inbound, reply);
    }

    @Override
    public String formatReply(CsInboundMessage inbound, String reply, CsChannel channel) {
        String plain = plainReplyXml(inbound, reply);
        if (!encryptedMode(channel)) {
            return plain;
        }
        String encrypt = WecomCrypto.encrypt(channel.getEncodingAesKey(), channel.getCorpId(), plain);
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = String.valueOf(1000000000L + RANDOM.nextInt(1000000000));
        String signature = CsChannelSignature.sha1(
            channel.getChannelSecret(), String.valueOf(timestamp), nonce, encrypt);
        return "<xml>"
            + "<Encrypt>" + WechatMpChannelAdapter.cdata(encrypt) + "</Encrypt>"
            + "<MsgSignature>" + WechatMpChannelAdapter.cdata(signature) + "</MsgSignature>"
            + "<TimeStamp>" + timestamp + "</TimeStamp>"
            + "<Nonce>" + WechatMpChannelAdapter.cdata(nonce) + "</Nonce>"
            + "</xml>";
    }

    private String plainReplyXml(CsInboundMessage inbound, String reply) {
        long now = System.currentTimeMillis() / 1000;
        return "<xml>"
            + "<ToUserName>" + WechatMpChannelAdapter.cdata(inbound.externalUserId()) + "</ToUserName>"
            + "<FromUserName>" + WechatMpChannelAdapter.cdata(inbound.toUser()) + "</FromUserName>"
            + "<CreateTime>" + now + "</CreateTime>"
            + "<MsgType>" + WechatMpChannelAdapter.cdata("text") + "</MsgType>"
            + "<Content>" + WechatMpChannelAdapter.cdata(reply) + "</Content>"
            + "</xml>";
    }

    // ---------------- URL 验证回显 ----------------

    @Override
    public String echo(Map<String, String> params) {
        return params.get("echostr");
    }

    @Override
    public String echo(Map<String, String> params, CsChannel channel) {
        String echo = params.get("echostr");
        if (!encryptedMode(channel)) {
            return echo;
        }
        if (echo == null || echo.isBlank()) {
            return "";
        }
        // 加密模式:企微要求把 echostr 解密后以明文返回
        return WecomCrypto.decrypt(channel.getEncodingAesKey(), channel.getCorpId(), echo);
    }

    /**
     * 是否加密模式:配了 EncodingAESKey 即强制加密。
     */
    public static boolean encryptedMode(CsChannel channel) {
        return channel != null && channel.getEncodingAesKey() != null
            && !channel.getEncodingAesKey().isBlank();
    }
}
