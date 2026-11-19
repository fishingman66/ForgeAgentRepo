package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.compliance.CsComplianceService;
import com.forgeagent.customerservice.compliance.CsLocalePolicy;
import com.forgeagent.callcenter.common.CsPiiMasker;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsMessageReceipt;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsVisitor;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsMessageReceiptMapper;
import com.forgeagent.customerservice.mapper.CsVisitorMapper;
import com.forgeagent.callcenter.common.CsEventEnvelope;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访客会话与机器人自助(C0/C4)。文本域的核心入口,语音域(CallBusinessBinder)建会话也复用本类。
 *
 * <p>会话复用 agent_session(场景 scene=cs),归属以 visitorId 为准;
 * 知识库零命中走 fail-closed 兜底并按策略转人工,不让模型自由发挥;
 * C4 起消息在落库与进入模型前统一做 PII 脱敏,兜底话术按渠道语种选择。</p>
 *
 * <p>扩展方向:多轮记忆(已在 V2 记忆体系改造中接入)、人工坐席接管后的会话切换、
 * 外呼方向会话(direction=outbound)、渠道级模型路由,都在 createSession / send
 * 两个入口上叠加;requireConversation / maskContent / dedupKey / saveReceipt
 * 是对外复用的四个口径方法,改动需同步语音域调用方。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsVisitorService {

    private final CsChannelGuard guard;
    private final CsVisitorMapper visitorMapper;
    private final CsConversationMapper conversationMapper;
    private final CsMessageReceiptMapper receiptMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final CsChatFacade chatFacade;
    private final CsKnowledgeGateway knowledgeGateway;
    private final CsRealtimeTransport transport;
    private final CsModuleProperties properties;
    private final CsTicketService ticketService;
    /** C2:已审核生效的 FAQ 优先命中,给确定性答案。 */
    private final CsKnowledgeAnswerService knowledgeAnswerService;
    /** C4:多语种兜底与合规擦除。 */
    private final CsLocalePolicy localePolicy;
    private final CsComplianceService complianceService;
    /** P0:敏感词词库过滤(入站拦截 / 出站打码)。 */
    private final CsSensitiveWordService sensitiveWordService;
    /** P1:语义缓存(只缓存白名单意图的通用问答)。 */
    private final CsSemanticCacheService semanticCacheService;
    /** P1:多轮槽位收集(默认关闭)。 */
    private final CsSlotFillingService slotFillingService;
    /** P1:知识缺口归并(零命中时累加)。 */
    private final CsKnowledgeGapService knowledgeGapService;
    /** P1:badcase(零命中/敏感词拦截/坐席忽略)。 */
    private final CsBadcaseService badcaseService;
    /** P2:配额判定(超限拒绝并转人工)。 */
    private final CsQuotaService quotaService;
    /** P2 收尾:访客附件归属校验。 */
    private final CsAttachmentService attachmentService;
    /** P3:多 Agent 意图路由(新会话首条消息生效)。 */
    private final CsAgentRouteService agentRouteService;
    /** A1:坐席辅助触发源,消息受理完成后异步发布(见 CsVisitorMessageSavedEvent)。 */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final CsTurnSignalService turnSignalService;
    /** M3 后续:图谱辅助检索,把访客口语扩展成领域实体词。 */
    private final CsKgQueryExpander kgQueryExpander;
    /** M4 后续:检索扩展效果指标。 */
    private final CsKgExpandMetricService kgExpandMetricService;

    /** 建立或复用活跃会话;返回 conversationId / visitorToken / reused。 */
    public com.forgeagent.customerservice.domain.vo.CsSendResultVo createSession(CsChannel channel, String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank() || externalUserId.length() > 128) {
            throw new ServiceException("访客标识无效");
        }
        CsVisitor visitor = visitorMapper.selectOne(new LambdaQueryWrapper<CsVisitor>()
            .eq(CsVisitor::getChannelCode, channel.getChannelCode())
            .eq(CsVisitor::getExternalUserId, externalUserId).last("limit 1"));
        if (visitor == null) {
            visitor = new CsVisitor();
            visitor.setChannelCode(channel.getChannelCode());
            visitor.setExternalUserId(externalUserId);
            visitor.setNickname("访客");
            visitor.setConsentMemory("0");
            visitor.setConsentExport("0");
            visitor.setLastActiveAt(new Date());
            visitor.setCreateTime(new Date());
            visitorMapper.insert(visitor);
        }

        CsConversation active = conversationMapper.selectOne(new LambdaQueryWrapper<CsConversation>()
            .eq(CsConversation::getVisitorId, visitor.getId())
            .eq(CsConversation::getChannelCode, channel.getChannelCode())
            .ne(CsConversation::getStatus, "ENDED").last("limit 1"));
        if (active != null) {
            return sessionView(active, visitor, true);
        }

        AgentSession session = new AgentSession();
        session.setAgentId(channel.getAgentId());
        session.setUserId(channel.getOwnerUserId());
        session.setTitle("客服会话");
        session.setMessageCount(0);
        session.setScene("cs");
        session.setVisitorId(visitor.getId());
        session.setChannelCode(channel.getChannelCode());
        sessionMapper.insert(session);

        CsConversation conv = new CsConversation();
        conv.setAgentSessionId(session.getId());
        // P3:新建会话尚未确定接待 Agent,首条消息再判定
        conv.setAgentRouted("0");
        conv.setVisitorId(visitor.getId());
        conv.setChannelCode(channel.getChannelCode());
        conv.setWorkspaceId(channel.getWorkspaceId());
        conv.setAgentId(channel.getAgentId());
        conv.setStatus("ACTIVE");
        conv.setLastVisitorAt(new Date());
        conv.setCreateTime(new Date());
        // D1:会话创建时判定一次灰度版本并锁进会话,同会话后续轮次沿用(ADR-03)
        conv.setPinnedVersion(chatFacade.decidePinnedVersion(channel.getAgentId(), visitor.getId()));
        conversationMapper.insert(conv);
        transport.open(new CsRealtimeTransport.CsPeer(conv.getId(), visitor.getId(), channel.getChannelCode()));
        return sessionView(conv, visitor, false);
    }

    /** 无附件的兼容入口(渠道回调等不涉及附件的链路走这条)。 */
    public com.forgeagent.customerservice.domain.vo.CsSendResultVo send(CsChannel channel, Long conversationId, Long visitorId,
                                    String clientMsgId, String content) {
        return send(channel, conversationId, visitorId, clientMsgId, content, List.of());
    }

    /** 发消息:幂等受理 → PII 脱敏 → 知识检索 → 机器人回答 / 多语种兜底转人工。 */
    public com.forgeagent.customerservice.domain.vo.CsSendResultVo send(CsChannel channel, Long conversationId, Long visitorId,
                                    String clientMsgId, String content, List<Long> attachmentIds) {
        boolean hasAttachment = attachmentIds != null && !attachmentIds.isEmpty();
        if ((content == null || content.isBlank()) && !hasAttachment) {
            throw new ServiceException("消息内容不能为空");
        }
        if (content != null && content.length() > properties.getMaxMessageChars()) {
            throw new ServiceException("消息内容过长");
        }
        content = content == null ? "" : maskContent(content);
        if (clientMsgId == null || clientMsgId.isBlank()) {
            clientMsgId = java.util.UUID.randomUUID().toString();
        }
        CsConversation conv = requireConversation(channel, conversationId, visitorId);
        long dedupKey = dedupKey(conv, conversationId);
        // P2 收尾:附件归属校验——不校验就能把别人的单据挂到自己的消息上
        attachmentService.assertOwned(channel.getWorkspaceId(), conversationId, attachmentIds);

        CsMessageReceipt old = receiptMapper.selectOne(new LambdaQueryWrapper<CsMessageReceipt>()
            .eq(CsMessageReceipt::getSessionId, dedupKey)
            .eq(CsMessageReceipt::getClientMsgId, clientMsgId).last("limit 1"));
        if (old != null) {
            com.forgeagent.customerservice.domain.vo.CsSendResultVo reused = new com.forgeagent.customerservice.domain.vo.CsSendResultVo();
            // reused.put("acceptance", "ACCEPTED");  // Vo 化裁剪:常量 ACCEPTED
            reused.setReused(true);
            reused.setClientMsgId(clientMsgId);
            reused.setMessageId(old.getMessageId());
            reused.setContent(old.getReplyContent() == null ? "" : old.getReplyContent());
            return reused;
        }

        // P2 收尾:纯附件消息——附件不进模型上下文,AI 看不到图,直接转人工由坐席看图
        if (content.isBlank() && hasAttachment) {
            return attachmentOnlyReply(channel, conv, dedupKey, clientMsgId, attachmentIds.size());
        }

        // P3:新会话首条消息确定接待 Agent——即使这条走的是 FAQ 或兜底,后续也用同一个 Agent
        Long agentId = agentIdForConv(conv, channel, content);

        // P0:敏感词入站——BLOCK 不送检索与模型,直接转人工;MASK 打码后继续
        CsSensitiveWordService.Hit inboundHit = sensitiveWordService.check(channel.getWorkspaceId(), content);
        if (inboundHit.blocked()) {
            return sensitiveBlock(channel, conv, dedupKey, clientMsgId, content, inboundHit.words());
        }
        if (inboundHit.masked()) {
            content = sensitiveWordService.mask(content, inboundHit.words());
        }

        // B1:每轮信号一次计算入账(意图/情绪/重复进线/轮次),写会话字段随既有 updateById 落库
        com.forgeagent.customerservice.domain.CsTurnSignals signals =
            turnSignalService.compute(channel.getWorkspaceId(), conv, content);
        // B2:转人工规则引擎收口(关键词/情绪阈值/重复进线/轮次上限),规则命中即转,
        // 不跑检索与模型;A1 推荐照常发布,让坐席接单时已经有候选话术。
        // 敏感词 BLOCK 与知识零命中仍是流程内固有闸门(依赖后续结果),不进引擎
        CsHandoffRuleEngine.HandoffDecision decision = CsHandoffRuleEngine.evaluate(
            content, channel.getHandoffRules(), handoffKeywords(channel), signals);
        if (decision.hit()) {
            return keywordHandoff(channel, conv, dedupKey, clientMsgId, content,
                decision.ruleType(), decision.reason());
        }

        // P2:配额——空间当日 token 超限时拒绝并转人工(转人工不再消耗模型)
        CsQuotaService.Decision quota = quotaService.check(channel.getWorkspaceId());
        if (quota.blocked()) {
            return quotaBlocked(channel, conv, dedupKey, clientMsgId);
        }

        // P1:槽位收集(默认关闭)——命中表单时先收信息,收齐后转人工,不跑检索与模型
        if (Boolean.TRUE.equals(properties.getSlotFillingEnabled())) {
            CsSlotFillingService.SlotOutcome outcome = slotFillingService.advance(
                conv.getId(), channel.getWorkspaceId(), content);
            if (outcome != null) {
                return slotReply(channel, conv, dedupKey, clientMsgId, content, outcome);
            }
        }

        // C2:已审核且在生效期内的 FAQ 优先命中,给确定性答案,不调模型也不判零命中
        com.forgeagent.customerservice.domain.CsKnowledgeAnswer faq =
            knowledgeAnswerService.match(channel.getWorkspaceId(), content);
        String reply;
        Long messageId = null;
        boolean fallback = false;
        boolean faqHit = false;
        boolean cachedHit = false;
        if (faq != null) {
            reply = faq.getAnswer();
            faqHit = true;
        } else {
            // P1:语义缓存命中直接返回,跳过检索与模型调用(命中内容仍会经过出站敏感词过滤)
            CsSemanticCacheService.Lookup cached = semanticCacheService.find(
                channel.getWorkspaceId(), channel.getChannelCode(), content);
            if (cached.hit()) {
                reply = cached.answer();
                cachedHit = true;
            } else {
                List<Long> knowledgeIds = parseIds(channel.getKnowledgeIds());
                // M3 后续:图谱把访客口语扩展成领域实体词,只扩检索 query,缓存键与模型入参不变
                CsKgQueryExpander.Expansion expansion =
                    kgQueryExpander.expandDetailed(channel.getWorkspaceId(), content);
                // A2:链路同构检索(阈值/RRF/邻块/父块/预算),闸门判定与模型应答同源;
                // 免登录口径 callerWorkspaceId,不依赖 Sa-Token
                CsKnowledgeGateway.RetrievalOutcome outcome = knowledgeGateway.retrieve(
                    channel.getWorkspaceId(), knowledgeIds, expansion.query());
                List<KnowledgeSearchHitVo> hits = outcome.hits();
                // M4 后续:检索扩展效果埋点(对比扩展/未扩展的零命中率)
                kgExpandMetricService.record(channel.getWorkspaceId(),
                    !expansion.terms().isEmpty(), hits.isEmpty());

                // A1 批次A(过渡闸门):知识零命中不再是绝对兜底——Agent 配了工具时放行模型,
                // 让查询类意图有机会走 SQL/HTTP 工具应答;未配工具维持原兜底 + 建单口径
                if (hits.isEmpty() && !chatFacade.hasExecutableTools(agentId)) {
                    fallback = true;
                    knowledgeGateway.recordZeroHit(channel.getAgentId(), dedupKey,
                        channel.getOwnerUserId(), content, knowledgeIds);
                    // P1:同一问题归并成知识缺口,命中次数自动升级优先级
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
                } else {
                    ChatSendVo vo = chatFacade.send(new CsChatFacade.CsChatRequest(
                        conv.getAgentSessionId(), agentId, content,
                        channel.getOwnerUserId(), channel.getWorkspaceId(),
                        visitorId, channel.getChannelCode(), conv.getTicketId(),
                        knowledgeIds.isEmpty() ? null : new CsChatFacade.PrefetchedRetrieval(
                            expansion.query(), knowledgeIds, outcome.hits(), outcome.contextByChunk()),
                        guardContext(channel, conv, visitorId)));
                    reply = vo.getAssistantMessage() == null ? "" : vo.getAssistantMessage().getContent();
                    messageId = vo.getAssistantMessage() == null ? null : vo.getAssistantMessage().getId();
                    // A1 终态:运营信号改由链路权威零命中信号统一兜住,覆盖两类场景——
                    // ①A1B 工具放行分支(KB 零命中,模型经工具应答,缺口照记驱动 FAQ 补录,
                    // KB 级零命中日志由 agent 链内部落不重复写);②闸门有命中但链路检索零命中
                    // (渠道 KB 与 Agent KB 不一致、预取被弃用),这类此前会被漏记。
                    if (Boolean.TRUE.equals(vo.getZeroHit())) {
                        knowledgeGapService.record(channel.getWorkspaceId(), content);
                        badcaseService.record(channel.getWorkspaceId(), "ZERO_HIT", conv.getId(),
                            conv.getTicketId(), null, content, null);
                    }
                }
            }
        }

        // P0:敏感词出站——模型可能生成竞品名或违规承诺,BLOCK 整条替换为兜底,其余打码
        CsSensitiveWordService.Hit outboundHit = sensitiveWordService.check(channel.getWorkspaceId(), reply);
        if (outboundHit.blocked()) {
            complianceService.record("SENSITIVE_HIT", "SYSTEM", null, channel.getWorkspaceId(),
                channel.getChannelCode(), "direction=out level=BLOCK words=" + outboundHit.words());
            reply = "抱歉，这条回复需要人工确认，已为你转接人工客服。";
            fallback = true;
        } else if (outboundHit.masked()) {
            reply = sensitiveWordService.mask(reply, outboundHit.words());
        }

        // P1:缓存正常完成的回答——兜底文案、敏感词拦截、本身命中缓存三种情况都不写
        if (!cachedHit && !fallback && reply != null && !reply.isBlank()) {
            semanticCacheService.store(channel.getWorkspaceId(), channel.getChannelCode(), content, reply);
        }

        saveReceipt(dedupKey, clientMsgId, messageId, reply);
        conv.setLastVisitorAt(new Date());
        conversationMapper.updateById(conv);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CHAT_DONE, conv.getId(), conv.getTicketId(),
            Map.of("content", reply, "clientMsgId", clientMsgId, "fallback", fallback, "faqHit", faqHit)));
        // A1 坐席辅助触发源:异步旁路,推荐失败不影响访客主链路(幂等重放不发布)
        eventPublisher.publishEvent(new com.forgeagent.customerservice.event.CsVisitorMessageSavedEvent(
            conv.getId(), conv.getTicketId(), channel.getWorkspaceId(), channel.getAgentId(),
            channel.getChannelCode(), content, parseIds(channel.getKnowledgeIds())));

        com.forgeagent.customerservice.domain.vo.CsSendResultVo result = new com.forgeagent.customerservice.domain.vo.CsSendResultVo();
        // result.put("acceptance", "ACCEPTED");  // Vo 化裁剪:常量 ACCEPTED
        result.setReused(false);
        result.setConversationId(conv.getId());
        result.setSessionId(conv.getAgentSessionId());
        result.setClientMsgId(clientMsgId);
        result.setMessageId(messageId);
        result.setContent(reply);
        result.setFallback(fallback);
        result.setFaqHit(faqHit);
        result.setCached(cachedHit);
        result.setHandoff(conv.getTicketId() != null);
        return result;
    }

    /**
     * 规则触发的转人工(P0 差距 4.1 / B2 规则引擎):建单 + 回转接文案 + 推终态事件 + 发布 A1 推荐事件。
     * 与正常路径共用幂等回执与事件格式,前端不需要区分两条分支;reason 记录命中规则便于归因。
     */
    private com.forgeagent.customerservice.domain.vo.CsSendResultVo keywordHandoff(CsChannel channel, CsConversation conv,
                                               long dedupKey, String clientMsgId, String content,
                                               String handoffBy, String reason) {
        CsTicket ticket = ticketService.requestHandoff(conv, reason);
        String reply = "正在为你转接人工客服，请稍候。";
        saveReceipt(dedupKey, clientMsgId, null, reply);
        conv.setLastVisitorAt(new Date());
        conversationMapper.updateById(conv);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CHAT_DONE, conv.getId(), ticket.getId(),
            Map.of("content", reply, "clientMsgId", clientMsgId, "fallback", false,
                "faqHit", false, "handoff", true)));
        eventPublisher.publishEvent(new com.forgeagent.customerservice.event.CsVisitorMessageSavedEvent(
            conv.getId(), ticket.getId(), channel.getWorkspaceId(), channel.getAgentId(),
            channel.getChannelCode(), content, parseIds(channel.getKnowledgeIds())));

        com.forgeagent.customerservice.domain.vo.CsSendResultVo result = new com.forgeagent.customerservice.domain.vo.CsSendResultVo();
        // result.put("acceptance", "ACCEPTED");  // Vo 化裁剪:常量 ACCEPTED
        result.setReused(false);
        result.setConversationId(conv.getId());
        result.setSessionId(conv.getAgentSessionId());
        result.setClientMsgId(clientMsgId);
        result.setMessageId(null);
        result.setContent(reply);
        result.setFallback(false);
        result.setFaqHit(false);
        result.setHandoff(true);
        result.setHandoffBy(handoffBy);
        result.setTicketId(ticket.getId());
        return result;
    }

    /** C2:护栏上下文——identity_binding 未配置的渠道写操作工具会被 fail-closed 拦截。 */
    private CsChatFacade.GuardContext guardContext(CsChannel channel, CsConversation conv, Long visitorId) {
        String binding = channel.getIdentityBinding();
        return new CsChatFacade.GuardContext(conv.getId(), visitorId, channel.getWorkspaceId(),
            channel.getChannelCode(), binding != null && !binding.isBlank(), conv.getPinnedVersion());
    }

    /** 转人工关键词:渠道配置优先,空则平台默认(判定口径见 CsHandoffKeywords)。 */
    private String handoffKeywords(CsChannel channel) {
        String own = channel.getHandoffKeywords();
        return own == null || own.isBlank() ? properties.getHandoffKeywords() : own;
    }

    /**
     * 选 Agent(P3):会话首条消息判定一次并锁定,之后不再路由。
     *
     * <p>为什么不用 {@code agentSessionId} 判断:它在建会话时就写入了,不代表已经对话过。
     * 用显式的 {@code agent_routed} 标记,首条命中就切专家 Agent,未命中也锁定为默认 Agent——
     * 中途换 Agent 会换 agent_session,上下文整段丢失。</p>
     */
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

    /**
     * 纯附件消息(P2 收尾):附件不进模型上下文,AI 看不到图,直接转人工让坐席看图处理。
     *
     * <p>不发布 A1 推荐事件——没有文字内容可推荐。</p>
     */
    private com.forgeagent.customerservice.domain.vo.CsSendResultVo attachmentOnlyReply(CsChannel channel, CsConversation conv,
                                                    long dedupKey, String clientMsgId, int count) {
        CsTicket ticket = ticketService.requestHandoff(conv, "attachment_only");
        String reply = "已收到你上传的 " + count + " 个文件，正在为你转接人工客服处理。";
        saveReceipt(dedupKey, clientMsgId, null, reply);
        conv.setLastVisitorAt(new Date());
        conversationMapper.updateById(conv);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CHAT_DONE, conv.getId(), ticket.getId(),
            Map.of("content", reply, "clientMsgId", clientMsgId, "fallback", false,
                "faqHit", false, "handoff", true, "attachmentOnly", true)));

        com.forgeagent.customerservice.domain.vo.CsSendResultVo result = new com.forgeagent.customerservice.domain.vo.CsSendResultVo();
        // result.put("acceptance", "ACCEPTED");  // Vo 化裁剪:常量 ACCEPTED
        result.setReused(false);
        result.setConversationId(conv.getId());
        result.setSessionId(conv.getAgentSessionId());
        result.setClientMsgId(clientMsgId);
        result.setMessageId(null);
        result.setContent(reply);
        result.setFallback(false);
        result.setFaqHit(false);
        result.setCached(false);
        result.setHandoff(true);
        result.setHandoffBy("attachment");
        result.setAttachments(new java.util.ArrayList<>(java.util.Collections.nCopies(count, null))); // attachmentCount → attachments.size()
        result.setTicketId(ticket.getId());
        return result;
    }

    /**
     * 配额拦截(P2):当日用量超限时不再调模型,直接转人工。
     *
     * <p>不在这里预生成回复、也不走检索——配额的意义就是省钱,任何模型侧动作都违背目的。</p>
     */
    private com.forgeagent.customerservice.domain.vo.CsSendResultVo quotaBlocked(CsChannel channel, CsConversation conv,
                                             long dedupKey, String clientMsgId) {
        CsTicket ticket = ticketService.requestHandoff(conv, "quota_exceeded");
        String reply = "今日咨询量较大，已为你转接人工客服，请稍候。";
        saveReceipt(dedupKey, clientMsgId, null, reply);
        conv.setLastVisitorAt(new Date());
        conversationMapper.updateById(conv);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CHAT_DONE, conv.getId(), ticket.getId(),
            Map.of("content", reply, "clientMsgId", clientMsgId, "fallback", false,
                "faqHit", false, "handoff", true, "quotaBlocked", true)));
        complianceService.record("QUOTA_BLOCK", "SYSTEM", null, channel.getWorkspaceId(),
            channel.getChannelCode(), "daily token quota exceeded");

        com.forgeagent.customerservice.domain.vo.CsSendResultVo result = new com.forgeagent.customerservice.domain.vo.CsSendResultVo();
        // result.put("acceptance", "ACCEPTED");  // Vo 化裁剪:常量 ACCEPTED
        result.setReused(false);
        result.setConversationId(conv.getId());
        result.setSessionId(conv.getAgentSessionId());
        result.setClientMsgId(clientMsgId);
        result.setMessageId(null);
        result.setContent(reply);
        result.setFallback(false);
        result.setFaqHit(false);
        result.setCached(false);
        result.setHandoff(true);
        result.setHandoffBy("quota");
        result.setQuotaBlocked(true);
        result.setTicketId(ticket.getId());
        return result;
    }

    /**
     * 槽位收集回复(P1):追问 or 收齐转人工。
     *
     * <p>收齐后直接建单——退款/开票这类诉求本来就要人工核实,继续让模型对话没有意义;
     * 分析结果同时随回执返回,坐席能看到客户填的结构化信息。</p>
     */
    private com.forgeagent.customerservice.domain.vo.CsSendResultVo slotReply(CsChannel channel, CsConversation conv, long dedupKey,
                                          String clientMsgId, String content,
                                          CsSlotFillingService.SlotOutcome outcome) {
        String reply = outcome.prompt();
        if (outcome.done()) {
            ticketService.requestHandoff(conv, "slot_filled:" + outcome.formName());
        }
        saveReceipt(dedupKey, clientMsgId, null, reply);
        conv.setLastVisitorAt(new Date());
        conversationMapper.updateById(conv);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CHAT_DONE, conv.getId(), conv.getTicketId(),
            Map.of("content", reply, "clientMsgId", clientMsgId, "fallback", false,
                "faqHit", false, "slotFilling", true, "slotDone", outcome.done())));
        eventPublisher.publishEvent(new com.forgeagent.customerservice.event.CsVisitorMessageSavedEvent(
            conv.getId(), conv.getTicketId(), channel.getWorkspaceId(), channel.getAgentId(),
            channel.getChannelCode(), content, parseIds(channel.getKnowledgeIds())));

        com.forgeagent.customerservice.domain.vo.CsSendResultVo result = new com.forgeagent.customerservice.domain.vo.CsSendResultVo();
        // result.put("acceptance", "ACCEPTED");  // Vo 化裁剪:常量 ACCEPTED
        result.setReused(false);
        result.setConversationId(conv.getId());
        result.setSessionId(conv.getAgentSessionId());
        result.setClientMsgId(clientMsgId);
        result.setMessageId(null);
        result.setContent(reply);
        result.setFallback(false);
        result.setFaqHit(false);
        result.setCached(false);
        result.setSlotFilling(true);
        result.setSlotForm(outcome.formName());
        result.setSlotDone(outcome.done());
        result.setSlotCollected(outcome.collected());
        result.setHandoff(conv.getTicketId() != null);
        return result;
    }

    /**
     * 敏感词拦截(P0):原文不送检索与模型,直接建单转人工并回固定文案。
     *
     * <p>不发布 A1 推荐事件——违规原文不该成为推荐输入;审计记词条便于复盘是
     * 客户攻击还是词库误杀。</p>
     */
    private com.forgeagent.customerservice.domain.vo.CsSendResultVo sensitiveBlock(CsChannel channel, CsConversation conv,
                                               long dedupKey, String clientMsgId,
                                               String content, List<String> words) {
        CsTicket ticket = ticketService.requestHandoff(conv, "sensitive_word");
        String reply = "你的消息包含不适宜内容，已为你转接人工客服。";
        saveReceipt(dedupKey, clientMsgId, null, reply);
        conv.setLastVisitorAt(new Date());
        conversationMapper.updateById(conv);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CHAT_DONE, conv.getId(), ticket.getId(),
            Map.of("content", reply, "clientMsgId", clientMsgId, "fallback", false,
                "faqHit", false, "handoff", true, "blocked", true)));
        complianceService.record("SENSITIVE_HIT", "VISITOR", String.valueOf(conv.getVisitorId()),
            channel.getWorkspaceId(), channel.getChannelCode(),
            "direction=in level=BLOCK words=" + words);
        // P1:拦截也是 badcase 的一种——运营要看是客户攻击还是词库误杀
        badcaseService.record(channel.getWorkspaceId(), "SENSITIVE_HIT", conv.getId(),
            null, null, content, "words=" + words);

        com.forgeagent.customerservice.domain.vo.CsSendResultVo result = new com.forgeagent.customerservice.domain.vo.CsSendResultVo();
        // result.put("acceptance", "ACCEPTED");  // Vo 化裁剪:常量 ACCEPTED
        result.setReused(false);
        result.setConversationId(conv.getId());
        result.setSessionId(conv.getAgentSessionId());
        result.setClientMsgId(clientMsgId);
        result.setMessageId(null);
        result.setContent(reply);
        result.setFallback(false);
        result.setFaqHit(false);
        result.setHandoff(true);
        result.setHandoffBy("sensitive");
        result.setTicketId(ticket.getId());
        result.setBlocked(true);
        return result;
    }

    /** 主动转人工。 */
    public com.forgeagent.customerservice.domain.vo.CsHandoffViewVo handoff(CsChannel channel, Long conversationId, Long visitorId, String reason) {
        CsConversation conv = requireConversation(channel, conversationId, visitorId);
        return ticketService.handoffView(conv, reason == null ? "visitor_request" : reason);
    }

    /** 隐私擦除(C4):级联清理客服域数据并写合规审计。 */
    public void erase(CsChannel channel, Long conversationId, Long visitorId) {
        CsConversation conv = requireConversation(channel, conversationId, visitorId);
        complianceService.eraseConversation(conversationId, "VISITOR", String.valueOf(visitorId));
        transport.push(CsEventEnvelope.of(CsEventEnvelope.SYSTEM, conv.getId(), conv.getTicketId(),
            Map.of("message", "会话数据已擦除")));
        transport.close(conversationId, "erased");
    }

    public CsConversation requireConversation(CsChannel channel, Long conversationId, Long visitorId) {
        CsConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !channel.getChannelCode().equals(conv.getChannelCode())
            || !visitorId.equals(conv.getVisitorId())) {
            throw new ServiceException("会话不存在或无权访问");
        }
        return conv;
    }

    /** C4:供流式端点复用同一套脱敏与幂等口径。 */
    public String maskContent(String content) {
        return Boolean.FALSE.equals(properties.getPiiMaskEnabled()) ? content : CsPiiMasker.mask(content);
    }

    public long dedupKey(CsConversation conv, Long conversationId) {
        return conv.getAgentSessionId() != null && conv.getAgentSessionId() > 0
            ? conv.getAgentSessionId() : -conversationId;
    }

    public void saveReceipt(long dedupKey, String clientMsgId, Long messageId, String reply) {
        CsMessageReceipt receipt = new CsMessageReceipt();
        receipt.setSessionId(dedupKey);
        receipt.setClientMsgId(clientMsgId);
        receipt.setMessageId(messageId);
        receipt.setAcceptance("ACCEPTED");
        receipt.setReplyContent(reply);
        receipt.setCreateTime(new Date());
        receiptMapper.insert(receipt);
    }

    private com.forgeagent.customerservice.domain.vo.CsSendResultVo sessionView(CsConversation conv, CsVisitor visitor, boolean reused) {
        com.forgeagent.customerservice.domain.vo.CsSendResultVo data = new com.forgeagent.customerservice.domain.vo.CsSendResultVo();
        data.setConversationId(conv.getId());
        data.setSessionId(conv.getAgentSessionId());
        data.setStatus(conv.getStatus());
        data.setReused(reused);
        data.setVisitorToken(guard.issueVisitorToken(conv.getChannelCode(), visitor.getId()));
        return data;
    }

    public static List<Long> parseIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split("[,;]")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                log.warn("[CS] 忽略非法知识库ID: {}", part);
            }
        }
        return ids;
    }
}
