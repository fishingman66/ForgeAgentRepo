package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.channel.CsInboundMessage;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsVisitor;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsVisitorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 渠道消息分发(C3):把公众号/企微上行消息接到访客链路。
 *
 * <p>复用 createSession + send,渠道 MsgId 直接作为 clientMsgId,
 * 由 agent_cs_message_receipt 唯一键完成消息幂等,不另建去重表。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsChannelDispatchService {

    private final CsVisitorMapper visitorMapper;
    private final CsConversationMapper conversationMapper;
    private final CsVisitorService visitorService;
    private final CsChannelGuard guard;

    public String handle(CsChannel channel, CsInboundMessage inbound) {
        guard.checkQuota(channel);
        if (inbound == null || inbound.externalUserId() == null || inbound.externalUserId().isBlank()) {
            return "消息缺少访客标识";
        }
        if (inbound.content() == null || inbound.content().isBlank()) {
            return "暂不支持该类型消息,请发送文字";
        }
        // per_message 模式:每条消息独立开会话,先结束该访客活跃会话
        if ("per_message".equalsIgnoreCase(channel.getSessionMode())) {
            endActiveConversations(channel.getChannelCode(), inbound.externalUserId());
        }
        Map<String, Object> session = visitorService.createSession(channel, inbound.externalUserId());
        Object cidRaw = session.get("conversationId");
        if (cidRaw == null) {
            throw new ServiceException("会话创建失败");
        }
        Long conversationId = ((Number) cidRaw).longValue();
        CsConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || conv.getVisitorId() == null) {
            throw new ServiceException("会话归属解析失败");
        }
        Map<String, Object> result = visitorService.send(channel, conversationId,
            conv.getVisitorId(), inbound.msgId(), inbound.content());
        return String.valueOf(result.getOrDefault("content", ""));
    }

    private void endActiveConversations(String channelCode, String externalUserId) {
        CsVisitor visitor = visitorMapper.selectOne(new LambdaQueryWrapper<CsVisitor>()
            .eq(CsVisitor::getChannelCode, channelCode)
            .eq(CsVisitor::getExternalUserId, externalUserId).last("limit 1"));
        if (visitor == null) {
            return;
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<CsConversation>()
            .eq(CsConversation::getVisitorId, visitor.getId())
            .eq(CsConversation::getChannelCode, channelCode)
            .ne(CsConversation::getStatus, "ENDED")
            .set(CsConversation::getStatus, "ENDED"));
    }
}
