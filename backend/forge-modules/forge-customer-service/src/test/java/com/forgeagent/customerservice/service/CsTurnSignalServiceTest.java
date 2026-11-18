package com.forgeagent.customerservice.service;

import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.callcenter.common.sentiment.LexiconSentimentAnalyzer;
import com.forgeagent.callcenter.common.sentiment.SentimentAnalyzer;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsTurnSignals;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * B1:每轮信号计算——意图/情绪/重复进线/轮次一次算出并写入会话字段;
 * 情绪复用 callcenter-common 的词表分析器(与语音洞察同一口径);
 * 连击口径:得分不超阈值递增,超阈值清零。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CsTurnSignalServiceTest {

    @Mock
    private CsIntentService intentService;

    @Mock
    private ObjectProvider<SentimentAnalyzer> analyzerProvider;

    @Mock
    private CsConversationMapper conversationMapper;

    @Mock
    private AgentSessionMapper sessionMapper;

    @InjectMocks
    private CsTurnSignalService service;

    private CsConversation conversation() {
        CsConversation conv = new CsConversation();
        conv.setId(5L);
        conv.setVisitorId(11L);
        conv.setAgentSessionId(77L);
        return conv;
    }

    @Test
    void nextStreakIncrementsWhileNegativeAndResetsOtherwise() {
        assertEquals(1, CsTurnSignalService.nextStreak(0, -0.9, CsHandoffRuleEngine.DEFAULT_SENTIMENT_THRESHOLD));
        assertEquals(3, CsTurnSignalService.nextStreak(2, CsHandoffRuleEngine.DEFAULT_SENTIMENT_THRESHOLD,
            CsHandoffRuleEngine.DEFAULT_SENTIMENT_THRESHOLD));
        assertEquals(0, CsTurnSignalService.nextStreak(4, 0.6, CsHandoffRuleEngine.DEFAULT_SENTIMENT_THRESHOLD));
        assertEquals(0, CsTurnSignalService.nextStreak(0, 0.0, CsHandoffRuleEngine.DEFAULT_SENTIMENT_THRESHOLD));
    }

    @Test
    void computeFillsAllSignalsAndConversationFields() {
        CsConversation conv = conversation();
        conv.setSentimentStreak(1);
        CsIntentService.Match match = new CsIntentService.Match(
            3L, "ORDER_STATUS", "订单查询", "查订单,物流", "keyword", List.of());
        when(intentService.classify(anyLong(), anyString(), any())).thenReturn(match);
        when(analyzerProvider.getIfAvailable()).thenReturn(new LexiconSentimentAnalyzer());
        // 24h 内已有 2 次进线
        when(conversationMapper.selectCount(any())).thenReturn(2L);
        AgentSession session = new AgentSession();
        session.setMessageCount(7);
        when(sessionMapper.selectById(77L)).thenReturn(session);

        CsTurnSignals signals = service.compute(1L, conv, "这个太差了,我要投诉");

        assertEquals("ORDER_STATUS", signals.intentCode());
        assertEquals("订单查询", signals.intentName());
        assertEquals("NEGATIVE", signals.sentimentLabel());
        assertEquals(2, signals.repeatTotal24h());
        assertEquals(3, signals.turnCount());
        // 连击在旧值上递增(词表版对投诉类文本必判负)
        assertTrue(signals.sentimentStreak() >= 2);
        assertEquals("ORDER_STATUS", conv.getLastIntentCode());
        assertEquals(signals.sentimentStreak(), conv.getSentimentStreak());
        assertNotNull(conv.getLastSentimentScore());
    }

    @Test
    void missingAnalyzerDegradesToNeutral() {
        CsConversation conv = conversation();
        when(intentService.classify(anyLong(), anyString(), any())).thenReturn(null);
        when(analyzerProvider.getIfAvailable()).thenReturn(null);
        when(conversationMapper.selectCount(any())).thenReturn(0L);
        when(sessionMapper.selectById(77L)).thenReturn(null);

        CsTurnSignals signals = service.compute(1L, conv, "你好");

        assertEquals("NEUTRAL", signals.sentimentLabel());
        assertEquals(0.0, signals.sentimentScore(), 0.0001);
        assertEquals(0, signals.sentimentStreak());
        assertEquals(0, signals.turnCount());
    }
}
