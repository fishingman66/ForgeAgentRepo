package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.agent.api.customerservice.CsTraceExtender;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.customerservice.compliance.CsLocalePolicy;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.domain.CsMessageReceipt;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsMessageReceiptMapper;
import com.forgeagent.callcenter.common.CsEventEnvelope;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 逐字流式对话(C4):与 send 共用脱敏、幂等、FAQ 优先与零命中兜底口径。
 *
 * <p>知识命中时走 CsChatFacade.stream 把模型 delta 直接透传给 SSE;
 * FAQ / 兜底路径用单个 chat_chunk 输出确定性回复。流式结束后从 agent_message
 * 取最终助手消息写回执,避免依赖 delta 报文的内部结构。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsStreamService {

    private final CsVisitorService visitorService;
    private final CsChatFacade chatFacade;
    private final CsKnowledgeGateway knowledgeGateway;
    private final CsKnowledgeAnswerService knowledgeAnswerService;
    private final CsTicketService ticketService;
    private final CsLocalePolicy localePolicy;
    private final CsTraceExtender traceExtender;
    private final CsRealtimeTransport transport;
    private final CsConversationMapper conversationMapper;
    private final CsMessageReceiptMapper receiptMapper;
    private final AgentMessageMapper messageMapper;
    /** P1:语义缓存——流式侧同样要接,否则真实流量一条缓存都产生不了。 */
    private final CsSemanticCacheService semanticCacheService;
    /** P0:敏感词出站过滤,流式侧用于决定"这条回答能不能进缓存"。 */
    private final CsSensitiveWordService sensitiveWordService;
    /** P1:知识缺口归并(零命中时累加)。 */
    private final CsKnowledgeGapService knowledgeGapService;
    /** P1:badcase(零命中)。 */
    private final CsBadcaseService badcaseService;
    /** P3:多 Agent 意图路由。 */
    private final CsAgentRouteService agentRouteService;
    /** M3 后续:图谱辅助检索,把访客口语扩展成领域实体词。 */
    private final CsKgQueryExpander kgQueryExpander;
    /** M4 后续:检索扩展效果指标。 */
    private final CsKgExpandMetricService kgExpandMetricService;

    public void stream(CsChannel channel, Long conversationId, Long visitorId,
                       String clientMsgId, String content, StreamEmitter emitter) {
        if (content == null || content.isBlank()) {
            emitter.send("error", Map.of("message", "消息内容不能为空"));
            return;
        }
        content = visitorService.maskContent(content);
        if (clientMsgId == null || clientMsgId.isBlank()) {
            clientMsgId = java.util.UUID.randomUUID().toString();
        }
        CsConversation conv = visitorService.requireConversation(channel, conversationId, visitorId);
        long dedupKey = visitorService.dedupKey(conv, conversationId);

        CsMessageReceipt old = receiptMapper.selectOne(new LambdaQueryWrapper<CsMessageReceipt>()
            .eq(CsMessageReceipt::getSessionId, dedupKey)
            .eq(CsMessageReceipt::getClientMsgId, clientMsgId).last("limit 1"));
        if (old != null) {
            emitter.send("chat_done", Map.of("content",
                old.getReplyContent() == null ? "" : old.getReplyContent(), "reused", true));
            return;
        }

        // P3:新会话首条消息确定接待 Agent(即使首条走 FAQ / 兜底,后续也用同一个)
        Long agentId = agentIdForConv(conv, channel, content);

        CsKnowledgeAnswer faq = knowledgeAnswerService.match(channel.getWorkspaceId(), content);
        String reply;
        Long messageId = null;
        boolean fallback = false;
        boolean cachedHit = false;
        if (faq != null) {
            reply = faq.getAnswer();
            emitter.send("chat_chunk", Map.of("content", reply));
        } else {
            // P1:语义缓存命中则不走检索与模型,切片下发让观感与流式一致(不掺人为延迟)
            CsSemanticCacheService.Lookup cached = semanticCacheService.find(
                channel.getWorkspaceId(), channel.getChannelCode(), content);
            if (cached.hit()) {
                reply = cached.answer();
                cachedHit = true;
                emitter.send("chat_chunk", Map.of("content", reply));
            } else {
                List<Long> knowledgeIds = CsVisitorService.parseIds(channel.getKnowledgeIds());
                // M3 后续:图谱扩展只进检索 query,缓存键与模型入参不变
                CsKgQueryExpander.Expansion expansion =
                    kgQueryExpander.expandDetailed(channel.getWorkspaceId(), content);
                List<KnowledgeSearchHitVo> hits = knowledgeGateway.search(
                    channel.getWorkspaceId(), knowledgeIds, expansion.query());
                // M4 后续:检索扩展效果埋点
                kgExpandMetricService.record(channel.getWorkspaceId(),
                    !expansion.terms().isEmpty(), hits.isEmpty());
                if (hits.isEmpty()) {
                    fallback = true;
                    knowledgeGateway.recordZeroHit(channel.getAgentId(), dedupKey,
                        channel.getOwnerUserId(), content, knowledgeIds);
                    knowledgeGapService.record(channel.getWorkspaceId(), content);
                    badcaseService.record(channel.getWorkspaceId(), "ZERO_HIT", conv.getId(),
                        conv.getTicketId(), null, content, null);
                    reply = localePolicy.resolveFallback(channel, content);
                    if (reply == null || reply.isBlank()) {
                        reply = "抱歉,暂时没有找到相关答案,已为你转接人工客服。";
                    }
                    if (channel.getHandoffPolicy() != null && !"manual".equals(channel.getHandoffPolicy())) {
                        ticketService.requestHandoff(conv, "zero_hit");
                    }
                    emitter.send("chat_chunk", Map.of("content", reply));
                } else {
                    CsChatFacade.CsChatRequest request = new CsChatFacade.CsChatRequest(
                        conv.getAgentSessionId(), agentId, content,
                        channel.getOwnerUserId(), channel.getWorkspaceId(),
                        visitorId, channel.getChannelCode(), conv.getTicketId());
                    chatFacade.stream(request, tracedEmitter(channel, conv, visitorId, emitter));
                    AgentMessage last = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getSessionId, conv.getAgentSessionId())
                        .eq(AgentMessage::getRole, "assistant")
                        .orderByDesc(AgentMessage::getId).last("limit 1"));
                    reply = last == null ? "" : last.getContent();
                    messageId = last == null ? null : last.getId();
                }
            }
        }

        // P1:缓存正常完成的回答;流式 delta 已推送,这里只决定"能不能进缓存",不回头改已发内容
        if (!cachedHit && !fallback && reply != null && !reply.isBlank()) {
            CsSensitiveWordService.Hit outbound = sensitiveWordService.check(channel.getWorkspaceId(), reply);
            if (!outbound.blocked()) {
                String toCache = outbound.masked()
                    ? sensitiveWordService.mask(reply, outbound.words()) : reply;
                semanticCacheService.store(channel.getWorkspaceId(), channel.getChannelCode(), content, toCache);
            }
        }

        visitorService.saveReceipt(dedupKey, clientMsgId, messageId, reply);
        conv.setLastVisitorAt(new Date());
        conversationMapper.updateById(conv);
        emitter.send("chat_done", Map.of("content", reply, "clientMsgId", clientMsgId,
            "fallback", fallback, "cached", cachedHit));
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CHAT_DONE, conv.getId(), conv.getTicketId(),
            Map.of("content", reply, "clientMsgId", clientMsgId, "fallback", fallback)));
    }

    /** P3:会话首条消息判定一次并锁定接待 Agent(判定口径同 CsVisitorService)。 */
    private Long agentIdForConv(CsConversation conv, CsChannel channel, String content) {
        if ("1".equals(conv.getAgentRouted())) {
            return conv.getAgentId() != null ? conv.getAgentId() : channel.getAgentId();
        }
        Long routed = agentRouteService.resolveAgentId(channel, content);
        Long agentId = routed == null ? channel.getAgentId() : routed;
        conv.setAgentId(agentId);
        conv.setAgentRouted("1");
        conv.setUpdateTime(new Date());
        conversationMapper.updateById(conv);
        return agentId;
    }

    /** 在 runId 确定时回填 Trace 客服维度,其余事件原样透传。 */
    private StreamEmitter tracedEmitter(CsChannel channel, CsConversation conv,
                                        Long visitorId, StreamEmitter delegate) {
        return new StreamEmitter() {
            @Override
            public void send(String event, Object data) {
                delegate.send(event, data);
            }

            @Override
            public void bindRun(String runId, Long sessionId) {
                traceExtender.attachCustomerService(runId, channel.getChannelCode(),
                    visitorId, conv.getTicketId());
                delegate.bindRun(runId, sessionId);
            }

            @Override
            public void finish() {
                delegate.finish();
            }
        };
    }
}
