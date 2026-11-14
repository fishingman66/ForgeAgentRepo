package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.forgeagent.customerservice.domain.CsQualityCheck;
import com.forgeagent.customerservice.domain.CsQualityRule;
import com.forgeagent.customerservice.mapper.CsQualityCheckMapper;
import com.forgeagent.customerservice.mapper.CsQualityRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C8 质检幂等:同一 (workspace, ticket, source) 重放只更新,不重复插入。 */
@Tag("dev")
class CallTranscriptQualityServiceTest {

    private CsCallSegmentMapper segmentMapper;
    private CsQualityRuleMapper ruleMapper;
    private CsQualityCheckMapper checkMapper;
    private CallTranscriptQualityService service;

    @BeforeEach
    void setUp() {
        segmentMapper = mock(CsCallSegmentMapper.class);
        ruleMapper = mock(CsQualityRuleMapper.class);
        checkMapper = mock(CsQualityCheckMapper.class);
        service = new CallTranscriptQualityService(segmentMapper, ruleMapper, checkMapper);

        when(segmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            segment("我要投诉,太慢了")));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule("投诉", -20)));
        when(checkMapper.insert(any(CsQualityCheck.class))).thenAnswer(inv -> {
            ((CsQualityCheck) inv.getArgument(0)).setId(7L);
            return 1;
        });
    }

    @Test
    void firstInspectionInsertsCheck() {
        when(checkMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        long id = service.inspectCall(call());

        assertEquals(7L, id);
        verify(checkMapper, times(1)).insert(any(CsQualityCheck.class));
        verify(checkMapper, never()).updateById(any(CsQualityCheck.class));
    }

    @Test
    void replayUpdatesExistingInsteadOfDuplicating() {
        CsQualityCheck existing = new CsQualityCheck();
        existing.setId(7L);
        existing.setScore(100);
        existing.setReviewStatus("PASS");
        when(checkMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        long id = service.inspectCall(call());

        assertEquals(7L, id, "重放返回同一条记录");
        verify(checkMapper, never()).insert(any(CsQualityCheck.class));
        verify(checkMapper, times(1)).updateById(existing);
        assertEquals(80, existing.getScore(), "命中 -20 后重算为 80");
        assertEquals("PASS", existing.getReviewStatus(), "人工已复审的状态不被机器覆盖");
        assertEquals("投诉", existing.getHitRules());
    }

    @Test
    void callWithoutTicketIsNotInspected() {
        CsCall call = call();
        call.setTicketId(null);

        assertEquals(0L, service.inspectCall(call));
        verify(checkMapper, never()).selectOne(any(Wrapper.class));
    }

    private static CsCall call() {
        CsCall call = new CsCall();
        call.setId(9L);
        call.setCallId("FS-CALL-1");
        call.setWorkspaceId(1L);
        call.setTicketId(55L);
        return call;
    }

    private static CsCallSegment segment(String text) {
        CsCallSegment segment = new CsCallSegment();
        segment.setId(101L);
        segment.setCallId(9L);
        segment.setSpeaker("caller");
        segment.setText(text);
        segment.setSegmentKind(TransferTimelineService.KIND_TRANSCRIPT);
        segment.setIsFinal("1");
        return segment;
    }

    private static CsQualityRule rule(String pattern, int delta) {
        CsQualityRule rule = new CsQualityRule();
        rule.setPattern(pattern);
        rule.setRuleType("KEYWORD");
        rule.setScoreDelta(delta);
        rule.setEnabled("1");
        return rule;
    }
}
