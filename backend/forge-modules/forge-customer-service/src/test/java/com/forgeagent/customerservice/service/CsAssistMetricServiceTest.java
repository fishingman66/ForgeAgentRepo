package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.domain.CsSeatSuggestion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A5:采纳率口径(按推荐条数)与连续零采纳判定。 */
@Tag("dev")
class CsAssistMetricServiceTest {

    private CsSeatSuggestion sugg(String adopted) {
        CsSeatSuggestion row = new CsSeatSuggestion();
        row.setAdopted(adopted);
        return row;
    }

    @Test
    void threeZeroAdoptInARowTriggersRecycle() {
        assertTrue(CsAssistMetricService.consecutiveZeroAdopt(
            List.of(sugg("0"), sugg("0"), sugg("0"))));
    }

    @Test
    void anyAdoptedBreaksTheWindow() {
        assertFalse(CsAssistMetricService.consecutiveZeroAdopt(
            List.of(sugg("0"), sugg("1"), sugg("0"))));
        assertFalse(CsAssistMetricService.consecutiveZeroAdopt(
            List.of(sugg("1"), sugg("0"), sugg("0"))));
    }

    @Test
    void shortHistoryIsNotJudged() {
        assertFalse(CsAssistMetricService.consecutiveZeroAdopt(List.of(sugg("0"))));
        assertFalse(CsAssistMetricService.consecutiveZeroAdopt(List.of(sugg("0"), sugg("0"))));
        assertFalse(CsAssistMetricService.consecutiveZeroAdopt(null));
    }

    @Test
    void onlyWindowHeadIsJudged() {
        // 第 4 条被采纳不影响前 3 条的判定(调用方按最近 3 条取数)
        assertTrue(CsAssistMetricService.consecutiveZeroAdopt(
            List.of(sugg("0"), sugg("0"), sugg("0"), sugg("1"))));
    }

    @Test
    void adoptRateIsBySuggestionCount() {
        assertEquals(75.0, CsAssistMetricService.adoptRate(3, 4), 0.0001);
        assertEquals(33.33, CsAssistMetricService.adoptRate(1, 3), 0.0001);
        assertEquals(100.0, CsAssistMetricService.adoptRate(5, 5), 0.0001);
        assertEquals(0.0, CsAssistMetricService.adoptRate(0, 0), 0.0001);
    }
}
