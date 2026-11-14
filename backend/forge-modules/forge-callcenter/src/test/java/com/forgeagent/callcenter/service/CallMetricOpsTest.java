package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import com.forgeagent.callcenter.mapper.CsCallMetricDailyMapper;
import com.forgeagent.callcenter.mapper.CsCallOpsMetricMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C8 日指标扩维口径 + 重建幂等(同一自然日重跑结果一致,可直接对账)。 */
@Tag("dev")
class CallMetricOpsTest {

    private CsCallMapper callMapper;
    private CsCallMetricDailyMapper metricMapper;
    private CallMetricService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        callMapper = mock(CsCallMapper.class);
        metricMapper = mock(CsCallMetricDailyMapper.class);
        CsCallOpsMetricMapper opsMapper = mock(CsCallOpsMetricMapper.class);
        ObjectProvider<CsCallOpsMetricMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(opsMapper);
        when(opsMapper.countNegativeCalls(any(), any(), any())).thenReturn(1);
        when(opsMapper.avgVoiceQuality(any(), any(), any())).thenReturn(88.5);
        service = new CallMetricService(callMapper, metricMapper, provider);
    }

    @Test
    void computeAddsTransferAbandonWaitAndCrossTableMetrics() {
        long base = 1_000_000L;
        List<CsCall> calls = List.of(
            // 接通并转人工:等待 5 秒
            call(base, base + 5_000L, 60, "after_sales", 11L, "k1"),
            // 振铃未接(呼损)
            call(base, null, null, null, null, null),
            // 无振铃的脏数据:既不算呼损也不算接通
            call(null, null, null, null, null, null),
            // 接通未转人工
            call(base, base + 3_000L, 30, null, null, null));

        CsCallMetricDaily row = CallMetricService.compute(calls, 1, 88.5);

        assertEquals(4, row.getCallCount());
        assertEquals(2, row.getAnsweredCount());
        assertEquals(0, BigDecimal.valueOf(50.00).compareTo(row.getAnswerRate()));
        assertEquals(1, row.getTransferCount());
        assertEquals(0, BigDecimal.valueOf(25.00).compareTo(row.getTransferRate()));
        assertEquals(1, row.getAbandonCount(), "有振铃无接听才算呼损");
        assertEquals(0, BigDecimal.valueOf(25.00).compareTo(row.getAbandonRate()));
        assertEquals(4, row.getAvgWaitSec(), "(5+3)/2 秒");
        assertEquals(1, row.getNegativeCalls());
        assertEquals(0, BigDecimal.valueOf(25.00).compareTo(row.getNegativeRate()));
        assertEquals(0, BigDecimal.valueOf(88.50).compareTo(row.getQualityAvgScore()));
    }

    @Test
    void legacyComputeKeepsOldContract() {
        CsCallMetricDaily row = CallMetricService.compute(List.of(
            call(1_000L, 2_000L, 10, null, null, null)));
        assertEquals(0, row.getNegativeCalls(), "旧签名不带跨表数据,按 0 计");
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getQualityAvgScore()));
    }

    @Test
    void emptyDayDoesNotDivideByZero() {
        CsCallMetricDaily row = CallMetricService.compute(List.of(), 0, null);
        assertEquals(0, row.getCallCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getAnswerRate()));
        assertEquals(0, row.getAvgWaitSec());
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getNegativeRate()));
    }

    @Test
    void rebuildIsIdempotentForSameDay() {
        CsCallMetricDaily[] stored = new CsCallMetricDaily[1];
        when(callMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            call(1_000L, 4_000L, 30, "after_sales", null, null)));
        when(metricMapper.selectOne(any(Wrapper.class)))
            .thenAnswer(inv -> stored[0]);
        when(metricMapper.insert(any(CsCallMetricDaily.class))).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });
        when(metricMapper.updateById(any(CsCallMetricDaily.class))).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });

        Date day = new Date();
        CsCallMetricDaily first = service.rebuild(1L, day);
        CsCallMetricDaily second = service.rebuild(1L, day);

        assertEquals(first.getCallCount(), second.getCallCount());
        assertEquals(0, first.getTransferRate().compareTo(second.getTransferRate()));
        assertEquals(0, first.getQualityAvgScore().compareTo(second.getQualityAvgScore()));
        verify(metricMapper, times(1)).insert(any(CsCallMetricDaily.class));
        verify(metricMapper, times(1)).updateById(any(CsCallMetricDaily.class));
    }

    /** ringAt/answerAt 用相对毫秒构造;null 表示事件缺失。 */
    private static CsCall call(Long ringOffset, Long answerOffset, Integer duration,
                               String transferTo, Long ticketId, String recordingKey) {
        CsCall call = new CsCall();
        call.setId(System.nanoTime());
        call.setWorkspaceId(1L);
        call.setRingAt(ringOffset == null ? null : new Date(ringOffset));
        call.setAnswerAt(answerOffset == null ? null : new Date(answerOffset));
        call.setDurationSec(duration);
        call.setTransferTo(transferTo);
        call.setTicketId(ticketId);
        call.setRecordingOssKey(recordingKey);
        return call;
    }
}
