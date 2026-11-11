package com.forgeagent.rag.service.retrieve;

import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阈值策略:目标按实际执行阶段选,不可比的分不参与过滤,且要显式标记"阈值未生效"。
 */
@Tag("dev")
class ScoreThresholdPolicyTest {

    private static KnowledgeSearchHitVo hit(long id, Double vectorScore, Double rerankScore) {
        KnowledgeSearchHitVo vo = new KnowledgeSearchHitVo();
        vo.setChunkId(id);
        vo.setContent("chunk-" + id);
        vo.setVectorScore(vectorScore);
        vo.setRerankScore(rerankScore);
        vo.setScore(0.01639);
        return vo;
    }

    @Test
    void noThresholdKeepsEverythingAndAnnotatesType() {
        List<KnowledgeSearchHitVo> hits = new ArrayList<>(List.of(hit(1, 0.4, null), hit(2, 0.2, null)));
        ScoreThresholdPolicy.Decision d = ScoreThresholdPolicy.apply(hits, null, null);
        assertEquals(2, d.kept().size());
        // target 表达"本次可比的分数是什么",与是否有阈值无关;threshold 为 null 才是"没过滤"
        assertEquals(ScoreType.VECTOR_COSINE, d.target());
        assertNull(d.threshold());
        assertFalse(d.inactive());
        assertEquals("cosine", hits.get(0).getScoreType());
    }

    @Test
    void vectorThresholdFiltersLowCosine() {
        List<KnowledgeSearchHitVo> hits = new ArrayList<>(List.of(hit(1, 0.4, null), hit(2, 0.01, null)));
        ScoreThresholdPolicy.Decision d = ScoreThresholdPolicy.apply(hits, 0.05, null);
        assertEquals(ScoreType.VECTOR_COSINE, d.target());
        assertEquals(1, d.kept().size());
        assertEquals(1L, d.kept().get(0).getChunkId());
    }

    @Test
    void rerankScoreWinsOverVectorAndUsesRerankThreshold() {
        // 精排分低的即使余弦高也要被砍:精排是更可信的信号
        List<KnowledgeSearchHitVo> hits = new ArrayList<>(List.of(hit(1, 0.9, 0.1), hit(2, 0.2, 0.8)));
        ScoreThresholdPolicy.Decision d = ScoreThresholdPolicy.apply(hits, 0.05, 0.3);
        assertEquals(ScoreType.RERANK, d.target());
        assertEquals(1, d.kept().size());
        assertEquals(2L, d.kept().get(0).getChunkId());
        assertEquals("rerank", d.kept().get(0).getScoreType());
    }

    @Test
    void rerankThresholdFallsBackToCosineThreshold() {
        List<KnowledgeSearchHitVo> hits = new ArrayList<>(List.of(hit(1, 0.9, 0.4), hit(2, 0.9, 0.1)));
        ScoreThresholdPolicy.Decision d = ScoreThresholdPolicy.apply(hits, 0.2, null);
        assertEquals(ScoreType.RERANK, d.target());
        assertEquals(1, d.kept().size(), "未配精排阈值时应退化用库级阈值,而不是不过滤");
    }

    @Test
    void keywordOnlyHitsAreKeptButMarkedInactive() {
        // 只有全文命中:两种可比分数都没有,阈值必须显式标为未生效而不是悄悄砍或悄悄放
        List<KnowledgeSearchHitVo> hits = new ArrayList<>(List.of(hit(1, null, null), hit(2, null, null)));
        ScoreThresholdPolicy.Decision d = ScoreThresholdPolicy.apply(hits, 0.05, 0.3);
        assertNull(d.target());
        assertTrue(d.inactive(), "配了阈值却没有可比分数,必须能被告警/展示出来");
        assertEquals(2, d.kept().size());
        assertEquals("bm25", hits.get(0).getScoreType());
    }

    @Test
    void mixedHitsFallBackPerHit() {
        // 精排只覆盖了部分候选时,没精排分的那条按余弦判,而不是整批被精排阈值砍掉
        List<KnowledgeSearchHitVo> hits = new ArrayList<>(List.of(hit(1, 0.5, 0.9), hit(2, 0.5, null)));
        ScoreThresholdPolicy.Decision d = ScoreThresholdPolicy.apply(hits, 0.2, 0.8);
        assertEquals(ScoreType.RERANK, d.target());
        assertEquals(2, d.kept().size());
    }

    @Test
    void emptyInputIsSafe() {
        ScoreThresholdPolicy.Decision d = ScoreThresholdPolicy.apply(List.of(), 0.1, 0.1);
        assertTrue(d.kept().isEmpty());
        assertNull(d.target());
        assertFalse(d.inactive());
    }

    @Test
    void decideTargetIgnoresNonComparableScores() {
        assertNull(ScoreThresholdPolicy.decideTarget(List.of(hit(1, null, null))));
        assertEquals(ScoreType.VECTOR_COSINE, ScoreThresholdPolicy.decideTarget(List.of(hit(1, 0.1, null))));
        assertEquals(ScoreType.RERANK, ScoreThresholdPolicy.decideTarget(List.of(hit(1, 0.1, 0.2))));
    }
}
