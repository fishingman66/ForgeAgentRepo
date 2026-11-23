package com.forgeagent.customerservice.service;

import com.forgeagent.callcenter.common.CsRealtimeTransport;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsMessageReceipt;
import com.forgeagent.customerservice.domain.CsTurnSignals;
import com.forgeagent.customerservice.domain.vo.CsSendResultVo;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsMessageReceiptMapper;
import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.customerservice.compliance.CsComplianceService;
import com.forgeagent.customerservice.compliance.CsLocalePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-1 闸门集成测试:规则命中「要不要转」之后,坐席可用性决定「能不能转」。
 * 锁两件事:① 不可转时不建工单、返回留言回执且 handoff=false;
 * ② 可转时保持原 keywordHandoff 口径(建单 + handoff=true)。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CsVisitorHandoffGateTest {

    @Mock private CsChannelGuard guard;
    // 下面用真实需要的 mock;未用到的 final 依赖由 @InjectMocks 注入 null,本测试路径不触碰。
    @Mock private CsConversationMapper conversationMapper;
    @Mock private CsMessageReceiptMapper receiptMapper;
    @Mock private AgentSessionMapper sessionMapper;
    @Mock private CsChatFacade chatFacade;
    @Mock private CsKnowledgeGateway knowledgeGateway;
    @Mock private CsRealtimeTransport transport;
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
    @Mock private CsTurnSignalService turnSignalService;
    @Mock private CsKgQueryExpander kgQueryExpander;
    @Mock private CsKgExpandMetricService kgExpandMetricService;
    @Mock private CsSeatAvailabilityService seatAvailabilityService;

    @InjectMocks private CsVisitorService service;

    /**
     * CsVisitorService 用 @RequiredArgsConstructor,@InjectMocks 走构造器注入,
     * 不会注入 @Autowired 非 final 字段——这里显式补注入,模拟 Spring 容器行为。
     */
    @BeforeEach
    void injectGate() {
        org.springframework.test.util.ReflectionTestUtils.setField(
            service, "seatAvailabilityService", seatAvailabilityService);
    }

    private CsChannel channel;

    @BeforeEach
    void setUp() {
        channel = new CsChannel();
        channel.setChannelCode("web");
        channel.setWorkspaceId(1L);
        channel.setOwnerUserId(2L);
        channel.setAgentId(9L);
        channel.setHandoffKeywords("转人工");
        channel.setHandoffPolicy("keyword");

        CsConversation conv = new CsConversation();
        conv.setId(5L);
        conv.setChannelCode("web");
        conv.setVisitorId(11L);
        conv.setAgentSessionId(77L);
        conv.setAgentId(9L);
        conv.setAgentRouted("1");
        conv.setStatus("ACTIVE");
        lenient().when(conversationMapper.selectById(5L)).thenReturn(conv);
        lenient().when(properties.getMaxMessageChars()).thenReturn(2000);
        lenient().when(properties.getHandoffKeywords()).thenReturn("转人工");
        lenient().when(sensitiveWordService.check(eq(1L), any()))
            .thenReturn(new CsSensitiveWordService.Hit(CsSensitiveWordService.Level.NONE, List.of()));
        // 关键词命中 → decision.hit()=true,进入闸门
        lenient().when(turnSignalService.compute(any(), any(), any()))
            .thenReturn(new CsTurnSignals(null, null, "NEUTRAL", 0.0, 0, 1, 0));
        lenient().when(receiptMapper.insert(any(CsMessageReceipt.class))).thenReturn(1);
        lenient().when(conversationMapper.updateById(any(CsConversation.class))).thenReturn(1);
        com.forgeagent.customerservice.domain.CsTicket ticket =
            new com.forgeagent.customerservice.domain.CsTicket();
        ticket.setId(88L);
        lenient().when(ticketService.requestHandoff(any(CsConversation.class), any()))
            .thenReturn(ticket);
    }

    @Test
    void keywordHitButNoSeatReturnsMessageReceiptWithoutTicket() {
        when(seatAvailabilityService.evaluate(channel)).thenReturn(
            new CsSeatAvailabilityService.Availability(false, "当前非营业时间"));

        CsSendResultVo result = service.send(channel, 5L, 11L, "c1", "我要转人工", List.of());

        // 先验证闸门确实被调用(否则 @InjectMocks 未注入非 final 字段,测试本身无效)
        verify(seatAvailabilityService).evaluate(channel);
        assertFalse(result.getHandoff(), "不可转时不得标 handoff");
        assertNull(result.getTicketId(), "不可转时不得建工单");
        assertEquals("当前非营业时间", result.getMessage());
        assertTrue(result.getContent().contains("非营业时间"));
        verify(ticketService, never()).requestHandoff(any(CsConversation.class), any());
        verify(complianceService).record(eq("NO_AGENT_AVAILABLE"), eq("SYSTEM"), any(),
            eq(1L), eq("web"), any());
    }

    @Test
    void keywordHitWithSeatKeepsOriginalHandoff() {
        when(seatAvailabilityService.evaluate(channel)).thenReturn(CsSeatAvailabilityService.Availability.ok());

        CsSendResultVo result = service.send(channel, 5L, 11L, "c1", "我要转人工", List.of());

        assertTrue(result.getHandoff());
        assertEquals(88L, result.getTicketId());
        verify(ticketService).requestHandoff(any(CsConversation.class), any());
    }
}
