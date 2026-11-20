package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsKnowledgeAnswerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** B3 FAQ 候选生成:低置信度过滤 / 模型失败 fail-safe / 无渠道 fail-closed。 */
@Tag("dev")
class CsFaqGenerateServiceTest {

    private CsKnowledgeAnswerMapper answerMapper;
    private CsChannelMapper channelMapper;
    private CsChatFacade chatFacade;
    private CsModuleProperties properties;
    private CsFaqGenerateService service;

    @BeforeEach
    void setUp() {
        answerMapper = mock(CsKnowledgeAnswerMapper.class);
        channelMapper = mock(CsChannelMapper.class);
        chatFacade = mock(CsChatFacade.class);
        properties = new CsModuleProperties();
        properties.setCostPer1kTokens(0.01);
        service = new CsFaqGenerateService(answerMapper, channelMapper, chatFacade, properties);
    }

    private void channelWithAgent() {
        CsChannel c = new CsChannel();
        c.setWorkspaceId(7L);
        c.setStatus("1");
        c.setAgentId(99L);
        when(channelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(c));
    }

    private CsKnowledgeAnswer pending(long id, String q) {
        CsKnowledgeAnswer a = new CsKnowledgeAnswer();
        a.setId(id);
        a.setWorkspaceId(7L);
        a.setQuestion(q);
        a.setAnswer("");
        a.setStatus("PENDING");
        return a;
    }

    @Test
    void highConfidenceCandidateIsSavedAsPending() {
        channelWithAgent();
        when(answerMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(pending(1L, "怎么退款?")));
        when(chatFacade.draftAnswer(99L, "怎么退款?", null))
            .thenReturn("{\"answer\":\"在订单页申请退款\",\"citations\":[\"订单页有退款入口\"],\"confidence\":0.9}");

        CsFaqGenerateService.GenerateResult r = service.generate(7L, 20);
        assertEquals(1, r.candidates());
        assertEquals(1, r.generated());
        assertEquals(0, r.skipped());
        assertNotNull(r.estimatedCost());

        ArgumentCaptor<CsKnowledgeAnswer> cap = ArgumentCaptor.forClass(CsKnowledgeAnswer.class);
        verify(answerMapper).updateById(cap.capture());
        assertEquals("在订单页申请退款", cap.getValue().getAnswer());
        assertEquals("ai_generated", cap.getValue().getSource());
        assertEquals("PENDING", cap.getValue().getStatus());
        assertNotNull(cap.getValue().getCitations(), "引用应结构化落库");
        assertTrue(cap.getValue().getCitations().contains("订单页有退款入口"));
        assertEquals(0, new java.math.BigDecimal("0.90").compareTo(cap.getValue().getConfidence()));
    }

    @Test
    void lowConfidenceCandidateIsSkipped() {
        channelWithAgent();
        when(answerMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(pending(2L, "不确定的问题?")));
        when(chatFacade.draftAnswer(99L, "不确定的问题?", null))
            .thenReturn("{\"answer\":\"可能是A\",\"citations\":[],\"confidence\":0.4}");

        CsFaqGenerateService.GenerateResult r = service.generate(7L, 20);
        assertEquals(1, r.candidates());
        assertEquals(0, r.generated());
        assertEquals(1, r.skipped());
        verify(answerMapper, never()).updateById(any(CsKnowledgeAnswer.class));
    }

    @Test
    void modelFailureFailsSafe() {
        channelWithAgent();
        when(answerMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(pending(3L, "模型失败的问题?")));
        when(chatFacade.draftAnswer(any(), any(), any())).thenReturn(null);

        CsFaqGenerateService.GenerateResult r = service.generate(7L, 20);
        assertEquals(1, r.candidates());
        assertEquals(0, r.generated());
        assertEquals(1, r.skipped());
        verify(answerMapper, never()).updateById(any(CsKnowledgeAnswer.class));
    }

    @Test
    void noChannelFailsClosed() {
        when(channelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.generate(7L, 20));
    }

    @Test
    void estimateDoesNotCallModel() {
        when(answerMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
        CsFaqGenerateService.GenerateResult r = service.estimate(7L, 20);
        assertEquals(5, r.candidates());
        assertEquals(0, r.generated());
        verifyNoInteractions(chatFacade);
    }
}
