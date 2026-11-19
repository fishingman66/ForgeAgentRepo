package com.forgeagent.customerservice.service;

import com.forgeagent.callcenter.common.EvalFeedbackWriter;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsBadcase;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsBadcaseMapper;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测回流服务单测(批次 D2;方向 B 迁移后 mock EvalFeedbackWriter)。
 *
 * <p>锁四件事:①badcase 直写的是「待确认」用例;②采样只在比例命中、访客 ≥2 轮、
 * 无 badcase 记录三个条件同时成立时发生;③两条路都靠唯一键幂等;④审核不能跨空间。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsEvalFeedbackServiceTest {

    @Mock private EvalFeedbackWriter evalWriter;
    @Mock private CsBadcaseMapper badcaseMapper;
    @Mock private CsConversationMapper conversationMapper;

    private CsModuleProperties properties;
    private CsEvalFeedbackService service;

    @BeforeEach
    void setUp() {
        properties = new CsModuleProperties();
        service = new CsEvalFeedbackService(evalWriter, badcaseMapper, conversationMapper, properties);
        // 跨域读 agent_id 走 cs 侧会话 Mapper
        lenient().when(conversationMapper.selectById(55L)).thenReturn(conversation());
    }

    private static CsConversation conversation() {
        CsConversation conv = new CsConversation();
        conv.setId(55L);
        conv.setWorkspaceId(9L);
        conv.setAgentId(7L);
        conv.setAgentSessionId(77L);
        return conv;
    }

    private static CsBadcase badcase() {
        CsBadcase b = new CsBadcase();
        b.setId(31L);
        b.setWorkspaceId(9L);
        b.setConversationId(55L);
        b.setQuestion("退货怎么申请");
        b.setAnswer("在订单页点申请售后");
        return b;
    }

    @Test
    void ensureDatasetReusesExisting() {
        when(evalWriter.ensureDataset(eq(9L), eq(7L), eq(CsEvalFeedbackService.DATASET_NAME), anyString())).thenReturn(9L);
        assertEquals(9L, service.ensureDataset(9L, 7L));
        verify(evalWriter).ensureDataset(eq(9L), eq(7L), eq(CsEvalFeedbackService.DATASET_NAME), anyString());
    }

    @Test
    void ensureDatasetCreatesWhenMissing() {
        when(evalWriter.ensureDataset(eq(9L), eq(7L), eq(CsEvalFeedbackService.DATASET_NAME), anyString())).thenReturn(12L);
        assertEquals(12L, service.ensureDataset(9L, 7L));
    }

    @Test
    void badcaseIsPushedAsPendingCase() {
        when(evalWriter.ensureDataset(eq(9L), eq(7L), eq(CsEvalFeedbackService.DATASET_NAME), anyString())).thenReturn(9L);
        when(evalWriter.insertCase(eq(9L), eq("cs_badcase"), eq(31L), eq("退货怎么申请"), eq("在订单页点申请售后"), eq("0")))
            .thenReturn(101L);

        Long caseId = service.pushBadcase(badcase());

        assertEquals(101L, caseId);
        verify(evalWriter).insertCase(9L, "cs_badcase", 31L, "退货怎么申请", "在订单页点申请售后", "0");
    }

    @Test
    void badcaseWithoutQuestionIsRejected() {
        CsBadcase empty = badcase();
        empty.setQuestion("  ");
        assertThrows(ServiceException.class, () -> service.pushBadcase(empty));
        verify(evalWriter, never()).insertCase(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void sampleDisabledWhenRateIsZero() {
        properties.setEvalSampleRate(0.0);
        service.sampleFromConversation(conversation(), new CsTicket());
        verify(evalWriter, never()).countVisitorTurns(anyLong());
    }

    @Test
    void sampleSkippedWhenVisitorTurnsTooFew() {
        properties.setEvalSampleRate(1.0);
        when(evalWriter.lastMessage(77L, "user")).thenReturn(Map.of("content", "退货"));
        when(evalWriter.countVisitorTurns(77L)).thenReturn(1);

        service.sampleFromConversation(conversation(), new CsTicket());

        verify(evalWriter, never()).insertCase(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void sampleSkippedWhenBadcaseExists() {
        properties.setEvalSampleRate(1.0);
        when(evalWriter.lastMessage(77L, "user")).thenReturn(Map.of("content", "退货"));
        when(evalWriter.countVisitorTurns(77L)).thenReturn(3);
        when(badcaseMapper.selectCount(any())).thenReturn(1L);

        service.sampleFromConversation(conversation(), new CsTicket());

        verify(evalWriter, never()).insertCase(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void eligibleConversationIsSampled() {
        properties.setEvalSampleRate(1.0);
        when(evalWriter.lastMessage(77L, "user")).thenReturn(Map.of("content", "我买的衣服要退"));
        when(evalWriter.lastMessage(77L, "assistant")).thenReturn(Map.of("content", "已为您提交换货申请"));
        when(evalWriter.countVisitorTurns(77L)).thenReturn(3);
        when(badcaseMapper.selectCount(any())).thenReturn(0L);
        when(evalWriter.ensureDataset(eq(9L), eq(7L), eq(CsEvalFeedbackService.DATASET_NAME), anyString())).thenReturn(9L);
        when(evalWriter.insertCase(eq(9L), eq("cs_sampled"), eq(55L), eq("我买的衣服要退"), eq("已为您提交换货申请"), eq("0")))
            .thenReturn(202L);

        service.sampleFromConversation(conversation(), new CsTicket());

        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        verify(evalWriter).insertCase(eq(9L), eq("cs_sampled"), eq(55L), question.capture(), any(), eq("0"));
        assertEquals("我买的衣服要退", question.getValue(), "问题取访客最后一句");
        verify(evalWriter).insertCase(eq(9L), eq("cs_sampled"), eq(55L), any(), eq("已为您提交换货申请"), eq("0"));
    }

    @Test
    void samplingFailureNeverBreaksCloseFlow() {
        properties.setEvalSampleRate(1.0);
        when(evalWriter.lastMessage(77L, "user")).thenThrow(new IllegalStateException("db down"));
        // 不抛异常:采样是旁路能力,不能带崩结案
        service.sampleFromConversation(conversation(), new CsTicket());
    }

    @Test
    void reviewDelegatesToWriter() {
        service.review(101L, 9L, false);
        verify(evalWriter).review(101L, 9L, false, "1", "2");
    }

    @Test
    void reviewRejectsForeignWorkspacePropagates() {
        org.mockito.Mockito.doThrow(new ServiceException("评测用例不存在"))
            .when(evalWriter).review(101L, 9L, true, "1", "2");
        assertThrows(ServiceException.class, () -> service.review(101L, 9L, true));
    }
}
