package com.forgeagent.rag.service.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R3-4 门禁判决单测:ε 边界、不可比、degenerate、判决顺序
 *
 * @author ForgeAgent
 */
@Tag("dev")
class GateJudgeCoreTest {

    private static final String FP = "fingerprint-1";
    private static final String REV = "r1";

    private GateJudgeCore.Side side(double hitRate, double recall, int effective, int stale, int degraded) {
        return new GateJudgeCore.Side(hitRate, recall, effective, stale, degraded, FP, REV);
    }

    @Test
    void firstRunNeverBlocked() {
        GateJudgeCore.Verdict v = GateJudgeCore.judge(null, side(0.5, 0.6, 30, 0, 0));
        assertEquals(GateJudgeCore.State.PASSED, v.state());
        assertEquals(GateJudgeCore.Reason.BASELINE_RECORDED, v.reason());
    }

    @Test
    void insufficientCasesIsLogOnly() {
        GateJudgeCore.Verdict v = GateJudgeCore.judge(side(0.8, 0.9, 30, 0, 0),
            side(0.1, 0.1, 0, 0, 0));
        assertEquals(GateJudgeCore.State.LOG_ONLY, v.state());
        assertEquals(GateJudgeCore.Reason.INSUFFICIENT_CASES, v.reason());
    }

    @Test
    void incomparableWhenFingerprintOrRevisionDiffers() {
        GateJudgeCore.Side otherFp = new GateJudgeCore.Side(0.1, 0.1, 30, 0, 0, "other", REV);
        assertEquals(GateJudgeCore.Reason.NOT_COMPARABLE,
            GateJudgeCore.judge(side(0.8, 0.9, 30, 0, 0), otherFp).reason());
        GateJudgeCore.Side otherRev = new GateJudgeCore.Side(0.1, 0.1, 30, 0, 0, FP, "r2");
        assertEquals(GateJudgeCore.Reason.NOT_COMPARABLE,
            GateJudgeCore.judge(side(0.8, 0.9, 30, 0, 0), otherRev).reason());
    }

    @Test
    void staleRatioOverLimitIsLogOnly() {
        // 30 有效 + 6 过期 → 6/36 = 0.167 > 0.15
        assertEquals(GateJudgeCore.Reason.STALE_RATIO_EXCEEDED,
            GateJudgeCore.judge(side(0.8, 0.9, 30, 0, 0), side(0.8, 0.9, 30, 6, 0)).reason());
        // 30 有效 + 5 过期 → 5/35 ≈ 0.143 ≤ 0.15,不触发
        assertEquals(GateJudgeCore.Reason.WITHIN_TOLERANCE,
            GateJudgeCore.judge(side(0.8, 0.9, 30, 0, 0), side(0.8, 0.9, 30, 5, 0)).reason());
    }

    @Test
    void degradedCasesOnEitherSideIsLogOnly() {
        assertEquals(GateJudgeCore.Reason.DEGRADED_CASES,
            GateJudgeCore.judge(side(0.8, 0.9, 30, 0, 0), side(0.8, 0.9, 30, 0, 1)).reason());
        assertEquals(GateJudgeCore.Reason.DEGRADED_CASES,
            GateJudgeCore.judge(side(0.8, 0.9, 30, 0, 2), side(0.8, 0.9, 30, 0, 0)).reason());
    }

    @Test
    void regressionBeyondEpsilonBlocks() {
        // N=50 → ε=0.02;delta = 0.6-0.65 = -0.05 < -ε → BLOCKED
        GateJudgeCore.Verdict v = GateJudgeCore.judge(
            side(0.65, 0.7, 50, 0, 0), side(0.60, 0.70, 50, 0, 0));
        assertEquals(GateJudgeCore.State.BLOCKED, v.state());
        assertEquals(GateJudgeCore.Reason.METRICS_REGRESSED, v.reason());
        assertEquals(0.02, v.epsilon(), 1e-9);
        assertEquals(-0.05, v.delta(), 1e-9);
    }

    @Test
    void regressionRecallAlsoBlocks() {
        // HitRate 持平但 Recall 跌破 ε
        GateJudgeCore.Verdict v = GateJudgeCore.judge(
            side(0.65, 0.8, 50, 0, 0), side(0.65, 0.7, 50, 0, 0));
        assertEquals(GateJudgeCore.State.BLOCKED, v.state());
    }

    @Test
    void boundaryDeltaEqualsEpsilonPasses() {
        // N=100 → ε=0.02;delta = -0.02 恰好等于 -ε(delta < -ε - 1e-9 为假)→ PASSED
        GateJudgeCore.Verdict v = GateJudgeCore.judge(
            side(0.70, 0.8, 100, 0, 0), side(0.68, 0.80, 100, 0, 0));
        assertEquals(GateJudgeCore.State.PASSED, v.state());
        assertEquals(GateJudgeCore.Reason.WITHIN_TOLERANCE, v.reason());
    }

    @Test
    void smallSampleGetsLargeEpsilon() {
        // N=20 → ε = 1/20 = 0.05 > 0.02
        assertEquals(0.05, GateJudgeCore.tolerance(20), 1e-9);
        assertEquals(0.02, GateJudgeCore.tolerance(50), 1e-9);
        // N=20 下跌 0.03 在容差内
        assertEquals(GateJudgeCore.State.PASSED,
            GateJudgeCore.judge(side(0.60, 0.7, 20, 0, 0), side(0.57, 0.70, 20, 0, 0)).state());
    }
}
