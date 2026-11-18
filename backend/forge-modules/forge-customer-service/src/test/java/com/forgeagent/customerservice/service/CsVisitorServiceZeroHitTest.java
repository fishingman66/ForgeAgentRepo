package com.forgeagent.customerservice.service;

import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.domain.vo.AgentMessageVo;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.customerservice.compliance.CsComplianceService;
import com.forgeagent.customerservice.compliance.CsLocalePolicy;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsMessageReceipt;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsTurnSignals;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsMessageReceiptMapper;
import com.forgeagent.customerservice.mapper.CsVisitorMapper;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 A(A1B 过渡闸门):知识零命中时,Agent 配了工具放行模型应答(不兜底不建单),
 * 未配工具维持原兜底 + 建单口径。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CsVisitorServiceZeroHitTest {

    @Mock private CsChannelGuard guard;
    @Mock private CsVisitorMapper visitorMapper;
    @Mock private CsConversationMapper conversationMapper;
    @Mock private CsMessageReceiptMapper receiptMapper;
    @Mock private AgentSessionMapper sessionMapper;
    @Mock private AgentMessageMapper messageMapper;
    @Mock private CsChatFacade chatFacade;
    @Mock private CsKnowledgeGateway knowledgeGateway;
    @Mock private com.forgeagent.callcenter.common.CsRealtimeTransport transport;
    @Mock private CsModuleProperties properties;
    @Mock private CsTicketService ticketService;
    @Mock private CsKnowledgeAnswerService knowledgeAnswerService;
    @Mock private CsLocalePolicy localePolicy;
    @Mock private CsComplianceService complianceService;
    @Mock private CsSensitiveWordService sensitiveWordService;
    @Mock private CsSemanticCacheService semanticCacheService;
    @Mock private CsSlotFillingService slotFillingService;
    @Mock private CsKnowledgeGapService knowledgeGapService;
    @Mock private CsBadcaseService badcaseService;
    @Mock private CsQuotaService quotaService;
    @Mock private CsAttachmentService attachmentService;
    @Mock private CsAgentRouteService agentRouteService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CsKgQueryExpander kgQueryExpander;
    @Mock private CsKgExpandMetricService kgExpandMetricService;
    @Mock private CsTurnSignalService turnSignalService;

    @InjectMocks
    private CsVisitorService service;

    private CsChannel channel;
    private CsConversation conv;

    private static final String QUESTION = "帮我查一下物流到哪了";

    @BeforeEach
    void setUp() {
        channel = new CsChannel();
        channel.setChannelCode("web");
        channel.setWorkspaceId(1L);
        channel.setOwnerUserId(2L);
        channel.setAgentId(9L);
        channel.setHandoffPolicy("zero_hit");
        channel.setKnowledgeIds("3");

        conv = new CsConversation();
        conv.setId(5L);
        conv.setChannelCode("web");
        conv.setVisitorId(11L);
        conv.setAgentSessionId(77L);
        conv.setAgentId(9L);
        conv.setAgentRouted("1");
        conv.setStatus("ACTIVE");

        lenient().when(properties.getMaxMessageChars()).thenReturn(2000);
        lenient().when(conversationMapper.selectById(5L)).thenReturn(conv);
        lenient().when(sensitiveWordService.check(eq(1L), anyString()))
            .thenReturn(new CsSensitiveWordService.Hit(CsSensitiveWordService.Level.NONE, List.of()));
        lenient().when(quotaService.check(1L))
            .thenReturn(new CsQuotaService.Decision(CsQuotaService.Level.ALLOW, 0L, 100L));
        lenient().when(semanticCacheService.find(eq(1L), eq("web"), anyString()))
            .thenReturn(new CsSemanticCacheService.Lookup(false, null, null));
        lenient().when(kgQueryExpander.expandDetailed(eq(1L), anyString()))
            .thenReturn(new CsKgQueryExpander.Expansion(QUESTION, List.of()));
        // B1:信号服务打中性桩,确保规则引擎不触发(关键词/情绪/重复进线均不命中)
        lenient().when(turnSignalService.compute(anyLong(), any(CsConversation.class), anyString()))
            .thenReturn(new CsTurnSignals(null, null, "NEUTRAL", 0.0, 0, 0, 0));
        lenient().when(knowledgeGateway.retrieve(eq(1L), any(), anyString()))
            .thenReturn(new CsKnowledgeGateway.RetrievalOutcome(List.of(), Map.of()));
        lenient().when(receiptMapper.insert(any(CsMessageReceipt.class))).thenReturn(1);
        lenient().when(conversationMapper.updateById(any(CsConversation.class))).thenReturn(1);
    }

    private Map<String, Object> send() {
        return service.send(channel, 5L, 11L, "c1", QUESTION, List.of());
    }

    private ChatSendVo modelReply(Boolean zeroHit, Integer citationCount) {
        ChatSendVo vo = new ChatSendVo();
        AgentMessageVo message = new AgentMessageVo();
        message.setId(1001L);
        message.setContent("你的快递已在派送中，预计今天送达");
        vo.setAssistantMessage(message);
        vo.setZeroHit(zeroHit);
        vo.setCitationCount(citationCount);
        return vo;
    }

    private void stubModelReply(ChatSendVo vo) {
        lenient().when(chatFacade.send(any(CsChatFacade.CsChatRequest.class))).thenReturn(vo);
    }

    @Test
    void zeroHitWithoutToolsKeepsFallbackAndHandoff() {
        lenient().when(chatFacade.hasExecutableTools(9L)).thenReturn(false);
        lenient().when(localePolicy.resolveFallback(channel, QUESTION)).thenReturn("兜底文案");
        lenient().when(ticketService.requestHandoff(any(CsConversation.class), eq("zero_hit")))
            .thenReturn(new CsTicket());

        Map<String, Object> result = send();

        assertEquals("兜底文案", result.get("content"));
        assertTrue((Boolean) result.get("fallback"));
        verify(chatFacade, never()).send(any());
        verify(ticketService).requestHandoff(any(CsConversation.class), eq("zero_hit"));
        verify(knowledgeGapService).record(eq(1L), eq(QUESTION));
        verify(knowledgeGateway).recordZeroHit(eq(9L), eq(77L), eq(2L), eq(QUESTION), any());
    }

    @Test
    void zeroHitWithToolsLetsModelAnswerViaTool() {
        lenient().when(chatFacade.hasExecutableTools(9L)).thenReturn(true);
        // A1 终态:KB 零命中时链路回传权威零命中信号,CS 层据此记录运营信号
        stubModelReply(modelReply(true, 0));

        Map<String, Object> result = send();

        assertEquals("你的快递已在派送中，预计今天送达", result.get("content"));
        assertFalse((Boolean) result.get("fallback"));
        assertEquals(1001L, ((Number) result.get("messageId")).longValue());
        verify(ticketService, never()).requestHandoff(any(CsConversation.class), anyString());
        verify(knowledgeGateway, never()).recordZeroHit(anyLong(), anyLong(), anyLong(), anyString(), any());
        // A2:预取检索结果随请求透传,链路按 KB 集合一致性核对后复用
        ArgumentCaptor<CsChatFacade.CsChatRequest> captor =
            ArgumentCaptor.forClass(CsChatFacade.CsChatRequest.class);
        verify(chatFacade).send(captor.capture());
        CsChatFacade.PrefetchedRetrieval prefetched = captor.getValue().prefetched();
        org.junit.jupiter.api.Assertions.assertNotNull(prefetched);
        assertEquals(QUESTION, prefetched.query());
        assertEquals(List.of(3L), prefetched.knowledgeIds());
        assertTrue(prefetched.hits().isEmpty());
        // A1 终态:链路零命中信号兜住运营信号,驱动 FAQ 补录
        verify(knowledgeGapService).record(eq(1L), eq(QUESTION));
        verify(badcaseService).record(eq(1L), eq("ZERO_HIT"), eq(5L), any(), any(), eq(QUESTION), any());
        verify(semanticCacheService).store(eq(1L), eq("web"), eq(QUESTION), anyString());
    }

    @Test
    void chainZeroHitAfterGateHitRecordsGap() {
        // A1 终态新增覆盖:闸门有命中但链路检索零命中(渠道 KB 与 Agent KB 不一致、预取被弃用)——
        // 此前这类场景运营信号会被漏记,现在由链路信号兜住
        KnowledgeSearchHitVo hit = new KnowledgeSearchHitVo();
        hit.setChunkId(9L);
        hit.setContent("物流说明");
        hit.setScore(0.9);
        lenient().when(knowledgeGateway.retrieve(eq(1L), any(), anyString()))
            .thenReturn(new CsKnowledgeGateway.RetrievalOutcome(List.of(hit), Map.of()));
        stubModelReply(modelReply(true, 0));

        Map<String, Object> result = send();

        assertFalse((Boolean) result.get("fallback"));
        verify(knowledgeGapService).record(eq(1L), eq(QUESTION));
        verify(badcaseService).record(eq(1L), eq("ZERO_HIT"), eq(5L), any(), any(), eq(QUESTION), any());
        verify(ticketService, never()).requestHandoff(any(CsConversation.class), anyString());
    }

    @Test
    void normalAnsweredRoundSkipsGapRecording() {
        KnowledgeSearchHitVo hit = new KnowledgeSearchHitVo();
        hit.setChunkId(9L);
        hit.setContent("退货政策");
        hit.setScore(0.9);
        lenient().when(knowledgeGateway.retrieve(eq(1L), any(), anyString()))
            .thenReturn(new CsKnowledgeGateway.RetrievalOutcome(List.of(hit), Map.of()));
        stubModelReply(modelReply(false, 1));

        Map<String, Object> result = send();

        assertFalse((Boolean) result.get("fallback"));
        verify(knowledgeGapService, never()).record(anyLong(), anyString());
        verify(badcaseService, never()).record(any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void sessionMapperUnusedInSendPath() {
        lenient().when(chatFacade.hasExecutableTools(9L)).thenReturn(false);
        lenient().when(localePolicy.resolveFallback(channel, QUESTION)).thenReturn("兜底文案");
        lenient().when(ticketService.requestHandoff(any(CsConversation.class), eq("zero_hit")))
            .thenReturn(new CsTicket());
        send();
        verify(sessionMapper, never()).insert(any(AgentSession.class));
    }
}
