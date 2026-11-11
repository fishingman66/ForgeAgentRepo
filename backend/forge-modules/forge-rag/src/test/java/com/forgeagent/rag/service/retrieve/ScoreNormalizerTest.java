package com.forgeagent.rag.service.retrieve;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分数归一化与 RRF 口径。
 */
@Tag("dev")
class ScoreNormalizerTest {

    @Test
    void cosineMapsToUnitRange() {
        assertEquals(0.0, ScoreNormalizer.unitFromCosine(-1d), 1e-9);
        assertEquals(0.5, ScoreNormalizer.unitFromCosine(0d), 1e-9, "正交应落在 0.5");
        assertEquals(1.0, ScoreNormalizer.unitFromCosine(1d), 1e-9);
        assertEquals(0.75, ScoreNormalizer.unitFromCosine(0.5d), 1e-9);
    }

    @Test
    void nullAndNaNAreZero() {
        assertEquals(0.0, ScoreNormalizer.unitFromCosine(null), 1e-9);
        assertEquals(0.0, ScoreNormalizer.unitFromCosine(Double.NaN), 1e-9);
    }

    @Test
    void clampKeepsInsideUnitRange() {
        assertEquals(0.0, ScoreNormalizer.clamp01(-0.3), 1e-9);
        assertEquals(1.0, ScoreNormalizer.clamp01(1.7), 1e-9);
        assertEquals(0.0, ScoreNormalizer.clamp01(Double.NaN), 1e-9);
        assertEquals(0.4, ScoreNormalizer.clamp01(0.4), 1e-9);
    }

    @Test
    void rrfUsesOneBasedRankAndDefaultK() {
        assertEquals(1d / 61, ScoreNormalizer.rrfScore(1, 60), 1e-9);
        assertEquals(1d / 62, ScoreNormalizer.rrfScore(2, 60), 1e-9);
        // k 非正按默认 60;rank 小于 1 按 1
        assertEquals(1d / 61, ScoreNormalizer.rrfScore(1, 0), 1e-9);
        assertEquals(1d / 61, ScoreNormalizer.rrfScore(0, 60), 1e-9);
    }

    @Test
    void rrfStaysBelowTypicalThreshold() {
        // 记录这条事实:RRF 上限 ≈ 1/61 ≈ 0.0164,两路双命中约 0.0328,
        // 拿 0.05 这类"看似温和"的阈值去比它必然全空
        double best = ScoreNormalizer.rrfScore(1, 60) * 2;
        org.junit.jupiter.api.Assertions.assertTrue(best < 0.05,
            "RRF 双路第一名应低于 0.05,这正是历史上阈值误配导致零命中的原因");
    }
}
