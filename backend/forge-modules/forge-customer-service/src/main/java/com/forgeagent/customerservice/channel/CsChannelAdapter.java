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
}
