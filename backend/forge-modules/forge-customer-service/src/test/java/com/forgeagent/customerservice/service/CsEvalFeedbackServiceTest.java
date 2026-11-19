package com.forgeagent.customerservice.service;

import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsBadcase;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测回流服务单测(批次 D2)。
 *
 * <p>锁四件事:①badcase 直写的是「待确认」用例;②采样只在比例命中、访客 ≥2 轮、
 * 无 badcase 记录三个条件同时成立时发生;③两条路都靠唯一键幂等;④审核不能跨空间。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsEvalFeedbackServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private CsModuleProperties properties;
    private CsEvalFeedbackService service;

    @BeforeEach
    void setUp() {
        properties = new CsModuleProperties();
        service = new CsEvalFeedbackService(jdbc, properties);
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
        when(jdbc.queryForList(contains("FROM agent_eval_dataset"), eq(Long.class), any(Object[].class)))
            .thenReturn(List.of(9L));

        assertEquals(9L, service.ensureDataset(9L, 7L));
        verify(jdbc, never()).update(contains("INSERT INTO agent_eval_dataset"), any(Object[].class));
    }

    @Test
    void ensureDatasetCreatesWhenMissing() {
        when(jdbc.queryForList(contains("FROM agent_eval_dataset"), eq(Long.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForObject(contains("LAST_INSERT_ID"), eq(Long.class))).thenReturn(12L);

        assertEquals(12L, service.ensureDataset(9L, 7L));
        verify(jdbc).update(contains("INSERT INTO agent_eval_dataset"), any(Object[].class));
    }

    @Test
    void badcaseIsPushedAsPendingCase() {
        when(jdbc.queryForList(contains("FROM agent_cs_conversation"), eq(Long.class), any(Object[].class)))
            .thenReturn(List.of(7L));
        // ensureDataset 命中已有
        when(jdbc.queryForList(contains("FROM agent_eval_dataset"), eq(Long.class), any(Object[].class)))
            .thenReturn(List.of(9L));
        when(jdbc.queryForList(contains("FROM agent_eval_case"), eq(Long.class), any(Object[].class)))
            .thenReturn(List.of(101L));

        Long caseId = service.pushBadcase(badcase());

        assertEquals(101L, caseId);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT IGNORE INTO agent_eval_case"), args.capture());
        Object[] values = args.getValue();
        assertEquals(9L, values[0], "dataset id");
        assertEquals("退货怎么申请", values[1]);
        assertEquals("在订单页点申请售后", values[2]);
        assertTrue(java.util.Arrays.asList(values).contains("cs_badcase"));
        assertTrue(java.util.Arrays.asList(values).contains("0"), "必须写入待确认态");
    }

    @Test
    void badcaseWithoutQuestionIsRejected() {
        CsBadcase empty = badcase();
        empty.setQuestion("  ");

        assertThrows(ServiceException.class, () -> service.pushBadcase(empty));
        verify(jdbc, never()).update(contains("INSERT IGNORE INTO agent_eval_case"), any(Object[].class));
    }

    @Test
    void sampleDisabledWhenRateIsZero() {
        properties.setEvalSampleRate(0.0);

        service.sampleFromConversation(conversation(), new CsTicket());

        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), any(Object[].class));
        verify(jdbc, never()).update(contains("INSERT IGNORE INTO agent_eval_case"), any(Object[].class));
    }

    @Test
    void sampleSkippedWhenVisitorTurnsTooFew() {
        properties.setEvalSampleRate(1.0);
        when(jdbc.queryForList(contains("role=?"), any(Object[].class)))
            .thenReturn(List.of(Map.of("content", "退货")));
        when(jdbc.queryForObject(contains("FROM agent_message"), eq(Integer.class), any(Object[].class)))
            .thenReturn(1);

        service.sampleFromConversation(conversation(), new CsTicket());

        verify(jdbc, never()).update(contains("INSERT IGNORE INTO agent_eval_case"), any(Object[].class));
    }

    @Test
    void sampleSkippedWhenBadcaseExists() {
        properties.setEvalSampleRate(1.0);
        when(jdbc.queryForList(contains("role=?"), any(Object[].class)))
            .thenReturn(List.of(Map.of("content", "退货")));
        when(jdbc.queryForObject(contains("FROM agent_message"), eq(Integer.class), any(Object[].class)))
            .thenReturn(3);
        when(jdbc.queryForObject(contains("FROM agent_cs_badcase"), eq(Integer.class), any(Object[].class)))
            .thenReturn(1);

        service.sampleFromConversation(conversation(), new CsTicket());

        verify(jdbc, never()).update(contains("INSERT IGNORE INTO agent_eval_case"), any(Object[].class));
    }

    @Test
    void eligibleConversationIsSampled() {
        properties.setEvalSampleRate(1.0);
        when(jdbc.queryForList(contains("role=?"), any(Object[].class)))
            .thenReturn(List.of(Map.of("content", "我买的衣服要退")))
            .thenReturn(List.of(Map.of("content", "已为您提交换货申请")));
        when(jdbc.queryForObject(contains("FROM agent_message"), eq(Integer.class), any(Object[].class)))
            .thenReturn(3);
        when(jdbc.queryForObject(contains("FROM agent_cs_badcase"), eq(Integer.class), any(Object[].class)))
            .thenReturn(0);
        when(jdbc.queryForList(contains("FROM agent_eval_dataset"), eq(Long.class), any(Object[].class)))
            .thenReturn(List.of(9L));
        when(jdbc.queryForList(contains("FROM agent_eval_case"), eq(Long.class), any(Object[].class)))
            .thenReturn(List.of(202L));

        service.sampleFromConversation(conversation(), new CsTicket());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT IGNORE INTO agent_eval_case"), args.capture());
        Object[] values = args.getValue();
        assertEquals("我买的衣服要退", values[1], "问题取访客最后一句");
        assertEquals("已为您提交换货申请", values[2], "参考答案取坐席最后一句");
        assertTrue(java.util.Arrays.asList(values).contains("cs_sampled"));
    }

    @Test
    void samplingFailureNeverBreaksCloseFlow() {
        properties.setEvalSampleRate(1.0);
        when(jdbc.queryForList(contains("role=?"), any(Object[].class)))
            .thenThrow(new IllegalStateException("db down"));

        // 不抛异常:采样是旁路能力,不能带崩结案
        service.sampleFromConversation(conversation(), new CsTicket());
    }

    @Test
    void reviewRejectsForeignWorkspace() {
        when(jdbc.queryForList(contains("FROM agent_eval_case c"), any(Object[].class)))
            .thenReturn(List.of());

        assertThrows(ServiceException.class, () -> service.review(101L, 9L, true));
        verify(jdbc, never()).update(contains("UPDATE agent_eval_case"), any(Object[].class));
    }

    @Test
    void reviewUpdatesStatus() {
        when(jdbc.queryForList(contains("FROM agent_eval_case c"), any(Object[].class)))
            .thenReturn(List.of(Map.of("id", 101L)));

        service.review(101L, 9L, false);

        verify(jdbc).update(contains("UPDATE agent_eval_case"), eq("2"), eq(101L));
    }
}
