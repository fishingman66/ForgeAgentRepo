package com.forgeagent.callcenter.insight;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallInsight;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.engine.MockChannelControl;
import com.forgeagent.callcenter.engine.ivr.IntentionClassifier;
import com.forgeagent.callcenter.mapper.CsCallInsightMapper;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C4 洞察管道:情绪节流 / 游标单调 / 幂等重放 / 失败空转游标 / 自动转接防抖 /
 * 队列拒绝不反噬主链路。
 *
 * <p>Mapper 用内存桩(唯一键语义 + 插入计数),这样"重放不追加、游标不回退"
 * 是可断言的;真实 SQL 的唯一键约束另有 DDL 保证。</p>
 */
@Tag("dev")
class InsightPipelineTest {

    private static final Long CALL_PK = 9L;
    private static final String CALL_UUID = "FS-CALL-1";

    private CallcenterProperties properties;
    private CsCallInsightMapper insightMapper;
    private CsCallSegmentMapper segmentMapper;
    private ChannelVoiceSettings settings;
    private LiveChannelRegistry liveChannels;

    /** 内存表:key = callId|kind|itemKey。 */
    private final Map<String, CsCallInsight> store = new LinkedHashMap<>();
    /** 插入计数:key 同上,用于断言"重放不追加"。 */
    private final Map<String, Integer> inserts = new LinkedHashMap<>();

    private InsightPipeline pipeline;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new CallcenterProperties();
        properties.setInsightEnabled(true);
        properties.setInsightPoolSize(1);
        properties.setInsightQueueCapacity(8);
        properties.setInsightTimeoutMs(5000L);
        properties.setSentimentMinIntervalMs(0L);
        properties.setNegativeScoreThreshold(-0.35);
        properties.setAutoTransferOnNegative(false);

        insightMapper = mock(CsCallInsightMapper.class);
        segmentMapper = mock(CsCallSegmentMapper.class);
        settings = mock(ChannelVoiceSettings.class);
        liveChannels = new LiveChannelRegistry();

        when(settings.of(anyString()))
            .thenReturn(new ChannelVoiceSettings.Settings(List.of(), "complaint"));

        when(insightMapper.selectByKey(anyLong(), anyString(), anyString()))
            .thenAnswer(inv -> copy(store.get(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))));
        when(insightMapper.insert(any(CsCallInsight.class))).thenAnswer(inv -> {
            CsCallInsight row = inv.getArgument(0);
            String k = key(row.getCallId(), row.getKind(), row.getItemKey());
            store.put(k, copy(row));
            inserts.merge(k, 1, Integer::sum);
            return 1;
        });
        when(insightMapper.updateById(any(CsCallInsight.class))).thenAnswer(inv -> {
            CsCallInsight row = inv.getArgument(0);
            store.put(key(row.getCallId(), row.getKind(), row.getItemKey()), copy(row));
            return 1;
        });
        when(insightMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> store.values().stream()
            .filter(r -> InsightKind.SENTIMENT.name().equals(r.getKind()))
            .map(InsightPipelineTest::copy).toList());
    }

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.shutdown();
            pipeline = null;
        }
    }

    // ------------------------------------------------------------------
    // 情绪:落库 / 节流 / 只评主叫终稿
    // ------------------------------------------------------------------

    @Test
    void callerFinalSegmentProducesSentimentRow() {
        pipeline = pipeline(new LexiconSentimentAnalyzer(), null);
        pipeline.onSegment(call(), segment(101L, "caller", "1", "太慢了,我要投诉"));

        awaitUntil("情绪洞察落库", () -> store.containsKey(key(CALL_PK, "SENTIMENT", "turn-101")));
        CsCallInsight row = store.get(key(CALL_PK, "SENTIMENT", "turn-101"));
        assertEquals("NEGATIVE", row.getLabel());
        assertEquals(101L, row.getSegmentUntilSeq(), "游标=该分片 id");
        assertTrue(row.getScore().doubleValue() < -0.5);
        assertTrue(row.getPayload().contains("\"engine\":\"lexicon\""), row.getPayload());
    }

    @Test
    void throttleSkipsRapidTurns() {
        properties.setSentimentMinIntervalMs(60_000L);
        pipeline = pipeline(new LexiconSentimentAnalyzer(), null);

        pipeline.onSegment(call(), segment(101L, "caller", "1", "太慢了"));
        awaitUntil("首轮情绪落库", () -> store.containsKey(key(CALL_PK, "SENTIMENT", "turn-101")));
        pipeline.onSegment(call(), segment(102L, "caller", "1", "还是很慢"));

        sleep(200);
        assertNull(store.get(key(CALL_PK, "SENTIMENT", "turn-102")), "节流窗口内的分片不评估");
    }

    @Test
    void onlyCallerFinalSegmentsAreEvaluated() {
        pipeline = pipeline(new LexiconSentimentAnalyzer(), null);
        pipeline.onSegment(call(), segment(101L, "bot", "1", "您好,请问有什么可以帮您"));
        pipeline.onSegment(call(), segment(102L, "caller", "0", "太慢"));

        sleep(200);
        assertTrue(store.isEmpty(), "机器人话术与中间态分片都不进情绪管道");
    }

    // ------------------------------------------------------------------
    // 幂等 / 游标单调 / 失败空转
    // ------------------------------------------------------------------

    @Test
    void replayDoesNotAppendDuplicates() {
        pipeline = pipeline(new LexiconSentimentAnalyzer(), refundClassifier());
        when(segmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            segment(101L, "caller", "1", "我要退货")));

        pipeline.onCallEnded(call());
        awaitUntil("挂机洞察落库", () -> store.containsKey(key(CALL_PK, "QA_REPORT", "default")));
        int sizeAfterFirst = store.size();

        pipeline.onCallEnded(call());
        sleep(300);

        assertEquals(sizeAfterFirst, store.size(), "重放只更新不追加");
        assertEquals(1, inserts.get(key(CALL_PK, "INTENT", "final")), "意图行只插入一次");
    }

    @Test
    void staleReplayNeverRegressesCursor() {
        CsCallInsight seeded = new CsCallInsight();
        seeded.setCallId(CALL_PK);
        seeded.setKind("INTENT");
        seeded.setItemKey("final");
        seeded.setSegmentUntilSeq(500L);
        seeded.setLabel("OLD_INTENT");
        store.put(key(CALL_PK, "INTENT", "final"), seeded);

        pipeline = pipeline(new LexiconSentimentAnalyzer(), refundClassifier());
        when(segmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            segment(100L, "caller", "1", "我要退货")));

        pipeline.onCallEnded(call());
        awaitUntil("质检报告落库", () -> store.containsKey(key(CALL_PK, "QA_REPORT", "default")));

        CsCallInsight row = store.get(key(CALL_PK, "INTENT", "final"));
        assertEquals(500L, row.getSegmentUntilSeq(), "旧回放不得把游标推回去");
        assertEquals("OLD_INTENT", row.getLabel(), "旧回放不得覆盖已有结论");
    }

    @Test
    void analyzerFailureSpinsCursorWithErrorRow() {
        SentimentAnalyzer broken = new SentimentAnalyzer() {
            @Override
            public String code() {
                return "broken";
            }

            @Override
            public Result analyze(String text) {
                throw new IllegalStateException("模型不可用");
            }
        };
        pipeline = pipeline(broken, null);
        pipeline.onSegment(call(), segment(101L, "caller", "1", "太慢了"));

        awaitUntil("失败行落库", () -> store.containsKey(key(CALL_PK, "SENTIMENT", "turn-101")));
        CsCallInsight row = store.get(key(CALL_PK, "SENTIMENT", "turn-101"));
        assertEquals(InsightPipeline.LABEL_ERROR, row.getLabel());
        assertEquals(101L, row.getSegmentUntilSeq(), "失败也要前移游标,避免同分片反复重试");
        assertTrue(row.getPayload().contains("ANALYZE_FAILED"), row.getPayload());
    }

    // ------------------------------------------------------------------
    // 负面自动转接
    // ------------------------------------------------------------------

    @Test
    void autoTransferFiresOncePerCall() {
        properties.setAutoTransferOnNegative(true);
        MockChannelControl channel = new MockChannelControl(CALL_UUID, 0);
        liveChannels.register(CALL_UUID, channel);
        pipeline = pipeline(new LexiconSentimentAnalyzer(), null);

        pipeline.onSegment(call(), segment(101L, "caller", "1", "太慢了,我要投诉"));
        awaitUntil("首轮转接下发", () -> !channel.commandsOf("transfer").isEmpty());
        assertEquals("complaint", channel.commandsOf("transfer").get(0).arg());

        pipeline.onSegment(call(), segment(102L, "caller", "1", "还是没人管"));
        awaitUntil("第二轮情绪落库", () -> store.containsKey(key(CALL_PK, "SENTIMENT", "turn-102")));

        assertEquals(1, channel.commandsOf("transfer").size(), "同通话只触发一次转接");
        assertTrue(store.get(key(CALL_PK, "SENTIMENT", "turn-102")).getPayload()
            .contains("SKIPPED_DUPLICATE"), "第二次命中防抖闸门");
    }

    @Test
    void autoTransferWithoutLiveChannelOnlyRecordsDecision() {
        properties.setAutoTransferOnNegative(true);
        pipeline = pipeline(new LexiconSentimentAnalyzer(), null);

        pipeline.onSegment(call(), segment(101L, "caller", "1", "太慢了,我要投诉"));
        awaitUntil("情绪落库", () -> store.containsKey(key(CALL_PK, "SENTIMENT", "turn-101")));

        assertTrue(store.get(key(CALL_PK, "SENTIMENT", "turn-101")).getPayload()
            .contains("PENDING_NO_CHANNEL"), "无媒体面时只留痕,不声称已转接");
    }

    @Test
    void autoTransferOffByDefault() {
        pipeline = pipeline(new LexiconSentimentAnalyzer(), null);
        MockChannelControl channel = new MockChannelControl(CALL_UUID, 0);
        liveChannels.register(CALL_UUID, channel);

        pipeline.onSegment(call(), segment(101L, "caller", "1", "太慢了,我要投诉"));
        awaitUntil("情绪落库", () -> store.containsKey(key(CALL_PK, "SENTIMENT", "turn-101")));

        assertTrue(channel.commandsOf("transfer").isEmpty(), "开关默认关,不得自动转接");
        assertTrue(!store.get(key(CALL_PK, "SENTIMENT", "turn-101")).getPayload().contains("autoTransfer"),
            "未决策时 payload 不出现 autoTransfer 字段");
    }

    // ------------------------------------------------------------------
    // 挂机三件套
    // ------------------------------------------------------------------

    @Test
    void hangupBuildsIntentExtractAndQaReport() {
        when(settings.of(anyString()))
            .thenReturn(new ChannelVoiceSettings.Settings(List.of("orderNo"), "complaint"));
        pipeline = pipeline(new LexiconSentimentAnalyzer(), refundClassifier());
        when(segmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            segment(101L, "caller", "1", "订单号 AB123456 我要退货")));

        pipeline.onCallEnded(call());
        awaitUntil("质检报告落库", () -> store.containsKey(key(CALL_PK, "QA_REPORT", "default")));

        CsCallInsight intent = store.get(key(CALL_PK, "INTENT", "final"));
        assertEquals("refund", intent.getLabel());
        assertEquals(0, BigDecimal.valueOf(0.92).compareTo(intent.getScore()));

        CsCallInsight extract = store.get(key(CALL_PK, "EXTRACT", "orderNo"));
        assertNotNull(extract, "声明字段命中后应有独立行");
        assertTrue(extract.getPayload().contains("AB123456"), extract.getPayload());

        CsCallInsight report = store.get(key(CALL_PK, "QA_REPORT", "default"));
        assertTrue(report.getPayload().contains("\"segmentCount\":1"), report.getPayload());
        assertTrue(report.getPayload().contains("\"callerTurns\":1"), report.getPayload());
    }

    @Test
    void emptyTranscriptStillProducesReport() {
        pipeline = pipeline(new LexiconSentimentAnalyzer(), refundClassifier());
        when(segmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        pipeline.onCallEnded(call());
        awaitUntil("质检报告落库", () -> store.containsKey(key(CALL_PK, "QA_REPORT", "default")));

        assertEquals(InsightPipeline.LABEL_UNKNOWN,
            store.get(key(CALL_PK, "INTENT", "final")).getLabel(), "无转写时意图记 UNKNOWN 而非静默");
        assertEquals(0L, store.get(key(CALL_PK, "QA_REPORT", "default")).getSegmentUntilSeq());
    }

    // ------------------------------------------------------------------
    // 开关与降级
    // ------------------------------------------------------------------

    @Test
    void disabledPipelineDoesNothing() {
        properties.setInsightEnabled(false);
        pipeline = pipeline(new LexiconSentimentAnalyzer(), refundClassifier());

        pipeline.onSegment(call(), segment(101L, "caller", "1", "太慢了"));
        pipeline.onCallEnded(call());

        sleep(150);
        assertTrue(store.isEmpty(), "关闸后不落任何洞察");
    }

    @Test
    void submitAfterShutdownDoesNotThrow() {
        pipeline = pipeline(new LexiconSentimentAnalyzer(), null);
        pipeline.shutdown();

        assertDoesNotThrow(() -> pipeline.onSegment(call(), segment(101L, "caller", "1", "太慢了")),
            "队列关闭/已满是拒绝而非抛异常——回调线程不能被拖垮");
    }

    // ------------------------------------------------------------------
    // 纯函数辅助
    // ------------------------------------------------------------------

    @Test
    void durationSecPrefersCallFieldAndFallsBackToSegmentTimeline() {
        CsCall call = call();
        assertEquals(96, InsightPipeline.durationSec(call, List.of(segment(1L, "caller", "1", "x"))),
            "CDR 已回填时以权威字段为准");

        call.setDurationSec(null);
        CsCallSegment last = segment(1L, "caller", "1", "x");
        last.setEndMs(7200L);
        assertEquals(7, InsightPipeline.durationSec(call, List.of(last)),
            "挂机瞬间 CDR 未到,用分片时间线兜底");
        assertEquals(0, InsightPipeline.durationSec(call, List.of()));
    }

    @Test
    void joinTextAndRecentTextsKeepSpeakerBoundary() {
        List<CsCallSegment> segments = List.of(
            segment(1L, "caller", "1", "我要退货"),
            segment(2L, "bot", "1", "好的"),
            segment(3L, "caller", "1", "三天内可以吗"),
            segment(4L, "caller", "1", "那运费谁出"));

        assertEquals("我要退货\n好的\n三天内可以吗\n那运费谁出", InsightPipeline.joinText(segments, null));
        assertEquals("我要退货\n三天内可以吗\n那运费谁出", InsightPipeline.joinText(segments, "caller"));
        assertEquals(List.of("三天内可以吗", "那运费谁出"), InsightPipeline.recentTexts(segments, 2));
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private InsightPipeline pipeline(SentimentAnalyzer analyzer, IntentionClassifier classifier) {
        InsightPipeline p = new InsightPipeline(properties, insightMapper, segmentMapper, settings,
            liveChannels, provider(analyzer), provider(classifier), provider(null), provider(null));
        p.init();
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static IntentionClassifier refundClassifier() {
        return (workspaceId, text, recent) -> text != null && text.contains("退货")
            ? new IntentionClassifier.Result("refund", 0.92) : null;
    }

    private static CsCall call() {
        CsCall call = new CsCall();
        call.setId(CALL_PK);
        call.setCallId(CALL_UUID);
        call.setWorkspaceId(1L);
        call.setChannelCode("FS_MOCK_CH1");
        call.setConversationId(77L);
        call.setDurationSec(96);
        call.setHangupCause("NORMAL_CLEARING");
        return call;
    }

    private static CsCallSegment segment(Long id, String speaker, String finalFlag, String text) {
        CsCallSegment segment = new CsCallSegment();
        segment.setId(id);
        segment.setCallId(CALL_PK);
        segment.setSpeaker(speaker);
        segment.setIsFinal(finalFlag);
        segment.setText(text);
        return segment;
    }

    private static String key(Long callId, String kind, String itemKey) {
        return callId + "|" + kind + "|" + itemKey;
    }

    private static CsCallInsight copy(CsCallInsight source) {
        if (source == null) {
            return null;
        }
        CsCallInsight target = new CsCallInsight();
        target.setId(source.getId());
        target.setCallId(source.getCallId());
        target.setWorkspaceId(source.getWorkspaceId());
        target.setKind(source.getKind());
        target.setItemKey(source.getItemKey());
        target.setSegmentUntilSeq(source.getSegmentUntilSeq());
        target.setLabel(source.getLabel());
        target.setScore(source.getScore());
        target.setPayload(source.getPayload());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    private static void awaitUntil(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        fail("等待超时: " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
