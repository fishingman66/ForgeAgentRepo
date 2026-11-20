package com.forgeagent.customerservice.channel;

import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;

import java.util.Map;

/** 渠道适配器契约(C3)。 */
public interface CsChannelAdapter {

    /** 与回调路径 {type} 对应的类型编码。 */
    String type();

    /** 验签 + 时间窗校验;失败直接拒绝,不泄露渠道信息。 */
    boolean verify(CsChannel channel, Map<String, String> params, CsModuleProperties properties);

    /** 解析上行消息;不支持的消息类型返回 content 为 null。 */
    CsInboundMessage parse(String body, Map<String, String> params);

    /** 组装渠道要求的回复报文。 */
    String formatReply(CsInboundMessage inbound, String reply);

    /** URL 验证时回显的内容。 */
    String echo(Map<String, String> params);

    // ---------------- P3 / B5:需要渠道配置与原始报文的场景 ----------------
    // 加密模式(企微安全模式)验签前像含报文里的 Encrypt,解析与回包要拿 EncodingAESKey,
    // 而旧签名拿不到 channel / body。这里用 default 重载扩展,明文适配器零改动。

    /**
     * 带原始报文的验签。默认退化为旧签名(明文模式行为不变)。
     */
    default boolean verify(CsChannel channel, Map<String, String> params, String body,
                           CsModuleProperties properties) {
        return verify(channel, params, properties);
    }

    /**
     * 带渠道配置的解析。默认退化为旧签名。
     */
    default CsInboundMessage parse(String body, Map<String, String> params, CsChannel channel) {
        return parse(body, params);
    }

    /**
     * 带渠道配置的组包(加密模式需要 EncodingAESKey 与 CorpID)。默认退化为旧签名。
     */
    default String formatReply(CsInboundMessage inbound, String reply, CsChannel channel) {
        return formatReply(inbound, reply);
    }

    /**
     * 带渠道配置的 URL 验证回显(加密模式需解密 echostr)。默认退化为旧签名。
     */
    default String echo(Map<String, String> params, CsChannel channel) {
        return echo(params);
    }
}
