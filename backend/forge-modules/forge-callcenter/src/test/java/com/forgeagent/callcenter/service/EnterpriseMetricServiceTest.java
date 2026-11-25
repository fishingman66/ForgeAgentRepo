package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.domain.CsEnterpriseMetricDaily;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-3 企业统计与话单补偿契约测试:聚合对账、退避阶梯、时间窗判定。
 */
@Tag("dev")
class EnterpriseMetricServiceTest {

    private CsCallMetricDaily row(int calls, int answered, int transfer, int abandon,
                                  int avgDuration, int negative, String quality) {
        CsCallMetricDaily r = new CsCallMetricDaily();
        r.setCallCount(calls);
        r.setAnsweredCount(answered);
        r.setTransferCount(transfer);
        r.setAbandonCount(abandon);
        r.setAvgDurationSec(avgDuration);
        r.setNegativeCalls(negative);
        r.setQualityAvgScore(quality == null ? null : new BigDecimal(quality));
        return r;
    }

    @Test
    void aggregateRecomputesRatesNotAverages() {
        // 两 workspace: 10通/8接通 + 10通/6接通 → 接通率应为 14/20=70%,不是 (80+60)/2
        CsEnterpriseMetricDaily out = EnterpriseMetricService.aggregate(
            7L, List.of(row(10, 8, 2, 1, 100, 3, "80.00"), row(10, 6, 1, 2, 200, 1, "90.00")));
        assertEquals(7L, out.getEnterpriseId());
        assertEquals(2, out.getWorkspaceCount());
        assertEquals(20, out.getCallCount());
        assertEquals(14, out.getAnsweredCount());
        assertEquals(new BigDecimal("70.00"), out.getAnswerRate(), "按总量重算比率");
        assertEquals(new BigDecimal("15.00"), out.getAbandonRate(), "3/20");
        assertEquals(150, out.getAvgDurationSec(), "加权平均时长 (100*10+200*10)/20");
        assertEquals(4, out.getNegativeCalls());
        assertEquals(new BigDecimal("85.00"), out.getQualityAvgScore(), "质检均分简单平均");
    }

    @Test
    void aggregateEmptyIsZeroSafe() {
        CsEnterpriseMetricDaily out = EnterpriseMetricService.aggregate(1L, List.of());
        assertEquals(0, out.getWorkspaceCount());
        assertEquals(0, out.getCallCount());
        assertEquals(BigDecimal.ZERO, out.getAnswerRate());
        assertEquals(BigDecimal.ZERO, out.getAbandonRate());
        assertEquals(0, out.getAvgDurationSec());
    }

    @Test
    void aggregateIgnoresNullRowsAndNullMetrics() {
        CsEnterpriseMetricDaily out = EnterpriseMetricService.aggregate(
            1L, java.util.Arrays.asList(null, row(4, 2, 0, 0, 50, 0, null)));
        assertEquals(1, out.getWorkspaceCount());
        assertEquals(4, out.getCallCount());
        assertEquals(new BigDecimal("50.00"), out.getAnswerRate());
        assertEquals(0, BigDecimal.ZERO.compareTo(out.getQualityAvgScore()), "数值为 0(忽略 scale 差异)");
    }

    @Test
    void backoffLadderMatchesReferenceIncreasingPolicy() {
        assertEquals(1, CdrCompensationService.backoffMinutes(0));
        assertEquals(5, CdrCompensationService.backoffMinutes(1));
        assertEquals(15, CdrCompensationService.backoffMinutes(2));
        assertEquals(60, CdrCompensationService.backoffMinutes(3));
        assertEquals(60, CdrCompensationService.backoffMinutes(99), "超出阶梯取最后一档");
    }

    @Test
    void dueWindowUsesBackoff() {
        long now = System.currentTimeMillis();
        Date nowDate = new Date(now);
        assertTrue(CdrCompensationService.due(nowDate, null, 0), "未重试过立即可试");
        assertFalse(CdrCompensationService.due(nowDate, new Date(now - 30_000L), 0), "1分钟窗口内不重试");
        assertTrue(CdrCompensationService.due(nowDate, new Date(now - 70_000L), 0), "超过1分钟可重试");
        assertFalse(CdrCompensationService.due(nowDate, new Date(now - 4 * 60_000L), 1), "5分钟窗口内不重试");
    }
}
