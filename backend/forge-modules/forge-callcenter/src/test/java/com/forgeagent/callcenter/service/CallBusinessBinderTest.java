package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.domain.CsCall;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** V2:挂机结案推进序列与指标聚合口径。 */
@Tag("dev")
class CallBusinessBinderTest {

    @Test
    void closeStepsFollowTicketTransitions() {
        assertEquals(List.of("WAITING_CONFIRM", "RESOLVED"),
            CallBusinessBinder.closeSteps("PROCESSING"));
        assertEquals(List.of("RESOLVED"), CallBusinessBinder.closeSteps("WAITING_CONFIRM"));
        assertEquals(List.of(), CallBusinessBinder.closeSteps("WAITING_AGENT"));
        assertEquals(List.of(), CallBusinessBinder.closeSteps("RESOLVED"));
        assertEquals(List.of(), CallBusinessBinder.closeSteps(null));
    }

    private CsCall call(Long ticketId, boolean answered, Integer duration, String recordingKey) {
        CsCall call = new CsCall();
        call.setTicketId(ticketId);
        call.setAnswerAt(answered ? new java.util.Date() : null);
        call.setDurationSec(duration);
        call.setRecordingOssKey(recordingKey);
        return call;
    }

    @Test
    void metricComputeKeepsAggregationContract() {
        List<CsCall> calls = List.of(
            call(10L, true, 96, "callcenter/recording/a.wav"),
            call(null, true, 30, "callcenter/recording/b.wav"),
            call(null, false, null, null));
        var row = CallMetricService.compute(calls);
        assertEquals(3, row.getCallCount());
        assertEquals(2, row.getAnsweredCount());
        assertEquals(0, BigDecimal.valueOf(66.67).compareTo(row.getAnswerRate()));
        assertEquals(63, row.getAvgDurationSec());
        assertEquals(0, BigDecimal.valueOf(66.67).compareTo(row.getBotResolveRate()));
        assertEquals(0, BigDecimal.valueOf(66.67).compareTo(row.getRecordingCoverage()));
    }

    @Test
    void metricComputeHandlesEmptyDay() {
        var row = CallMetricService.compute(List.of());
        assertEquals(0, row.getCallCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getAnswerRate()));
        assertEquals(0, row.getAvgDurationSec());
    }
}
