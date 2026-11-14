package com.forgeagent.callcenter.ops;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallInsight;
import com.forgeagent.callcenter.domain.CsCallProfile;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.mapper.CsCallInsightMapper;
import com.forgeagent.callcenter.mapper.CsCallProfileMapper;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.forgeagent.callcenter.service.TransferTimelineService;
import com.forgeagent.customerservice.domain.CsQualityCheck;
import com.forgeagent.customerservice.mapper.CsQualityCheckMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C8 通话画像:只读洞察/质检的事实,摘要确定性拼装,按 call_id 幂等。 */
@Tag("dev")
class CallProfileServiceTest {

    private CsCallInsightMapper insightMapper;
    private CsCallSegmentMapper segmentMapper;
    private CsCallProfileMapper profileMapper;
    private CsQualityCheckMapper qualityCheckMapper;
    private CallProfileService service;

    @BeforeEach
    void setUp() {
        insightMapper = mock(CsCallInsightMapper.class);
        segmentMapper = mock(CsCallSegmentMapper.class);
        profileMapper = mock(CsCallProfileMapper.class);
        qualityCheckMapper = mock(CsQualityCheckMapper.class);
        service = new CallProfileService(insightMapper, segmentMapper, profileMapper, qualityCheckMapper);

        when(insightMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            insight("INTENT", "final", "refund", null, "{\"engine\":\"RULE\"}"),
            insight("SENTIMENT", "turn-101", "NEUTRAL", 0.0, "{}"),
            insight("SENTIMENT", "turn-102", "NEGATIVE", -1.0, "{}"),
            insight("EXTRACT", "orderNo", "orderNo", null, "{\"value\":\"AB123456\"}"),
            // C5 逐片决策行不是画像口径
            insight("INTENT", "direct-101", "query_order", null, "{}")));
        when(segmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            segment("caller", TransferTimelineService.KIND_TRANSCRIPT),
            segment("caller", TransferTimelineService.KIND_TRANSCRIPT),
            segment("system", TransferTimelineService.KIND_RING)));

        CsQualityCheck check = new CsQualityCheck();
        check.setScore(88);
        when(qualityCheckMapper.selectOne(any(Wrapper.class))).thenReturn(check);
        when(profileMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(profileMapper.insert(any(CsCallProfile.class))).thenReturn(1);
    }

    @Test
    void profileAggregatesInsightAndQuality() {
        CsCallProfile profile = service.build(call());

        assertEquals("refund", profile.getIntentCode(), "只取挂机全文意图,不取逐片决策");
        assertEquals("NEGATIVE", profile.getSentimentLabel(), "取通话内最负面的一次");
        assertEquals(0, BigDecimal.valueOf(-1.0).compareTo(profile.getSentimentScore()));
        assertEquals(88, profile.getQualityScore());
        assertEquals(2, profile.getTurnCount(), "system 时间轴段不算主叫轮次");
        assertEquals("1", profile.getTransferFlag());
        assertEquals("0", profile.getResolvedFlag(), "有工单=机器人未自助解决");
        assertEquals("负面,转人工,未解决", profile.getTags());
        assertTrue(profile.getSummary().contains("意图 refund"), profile.getSummary());
        assertTrue(profile.getSummary().contains("已转人工 after_sales"), profile.getSummary());
        assertTrue(profile.getPayload().contains("AB123456"), profile.getPayload());
        assertTrue(profile.getPayload().contains("\"callerTurns\":2"), profile.getPayload());
    }

    @Test
    void secondBuildUpdatesSameRow() {
        CsCallProfile existing = new CsCallProfile();
        existing.setId(31L);
        when(profileMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        CsCallProfile profile = service.build(call());

        assertEquals(31L, profile.getId(), "幂等:重跑只更新同一行");
        verify(profileMapper, never()).insert(any(CsCallProfile.class));
        verify(profileMapper, times(1)).updateById(any(CsCallProfile.class));
    }

    @Test
    void missingQualityAndInsightDegradeGracefully() {
        when(insightMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(segmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(qualityCheckMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        // 裸通话:未转接、无工单、未接通,标签才应该是空的
        CsCall bare = new CsCall();
        bare.setId(9L);
        bare.setCallId("FS-CALL-1");
        bare.setWorkspaceId(1L);

        CsCallProfile profile = service.build(bare);

        assertNull(profile.getIntentCode());
        assertNull(profile.getSentimentLabel());
        assertNull(profile.getQualityScore());
        assertEquals(0, profile.getTurnCount());
        assertEquals("意图未识别；未接通", profile.getSummary(), "未接通不能写成机器人处理");
        assertEquals("", profile.getTags());
    }

    @Test
    void summaryAndTagsAreDeterministic() {
        assertEquals("意图 refund；通话 96 秒；情绪 NEGATIVE；机器人处理",
            CallProfileService.summaryOf("refund", 96,
                new CallProfileService.Sentiment("NEGATIVE", BigDecimal.valueOf(-1.0)), null, true));
        assertEquals("意图未识别；未接通", CallProfileService.summaryOf(null, null, null, null, false));
        assertEquals("意图未识别；已转人工 complaint",
            CallProfileService.summaryOf(null, null, null, "complaint", false), "转接优先于接听状态");
        assertEquals("转人工", CallProfileService.tagsOf(null, "1", "1"));
        assertEquals("", CallProfileService.tagsOf("POSITIVE", "0", "1"));
    }

    @Test
    void durationFallsBackToSegmentTimeline() {
        CsCall call = call();
        call.setDurationSec(null);
        CsCallSegment last = segment("caller", TransferTimelineService.KIND_TRANSCRIPT);
        last.setEndMs(7200L);
        assertEquals(7, CallProfileService.durationSec(call, List.of(last)));

        call.setDurationSec(96);
        assertEquals(96, CallProfileService.durationSec(call, List.of(last)), "CDR 回填后以权威值为准");
    }

    private static CsCall call() {
        CsCall call = new CsCall();
        call.setId(9L);
        call.setCallId("FS-CALL-1");
        call.setWorkspaceId(1L);
        call.setChannelCode("FS_MOCK_CH1");
        call.setCallerNumber("138****5678");
        call.setTicketId(55L);
        call.setTransferTo("after_sales");
        call.setDurationSec(96);
        return call;
    }

    private static CsCallInsight insight(String kind, String itemKey, String label,
                                         Double score, String payload) {
        CsCallInsight row = new CsCallInsight();
        row.setCallId(9L);
        row.setKind(kind);
        row.setItemKey(itemKey);
        row.setLabel(label);
        row.setScore(score == null ? null : BigDecimal.valueOf(score));
        row.setPayload(payload);
        return row;
    }

    private static CsCallSegment segment(String speaker, String kind) {
        CsCallSegment segment = new CsCallSegment();
        segment.setCallId(9L);
        segment.setSpeaker(speaker);
        segment.setSegmentKind(kind);
        segment.setIsFinal("1");
        segment.setText("文本");
        return segment;
    }
}
