package com.forgeagent.agent.service.support;

import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话链路的相关性过滤:阈值只能作用在可比分数上。
 *
 * <p>回归背景:此前直接把 {@code effectiveScore}(未开精排时是 RRF 融合分,上限约 0.033)
 * 与 {@code rag-min-score}(生产默认 0.05)比较,结果是<b>对话链路 RAG 恒零命中</b>——
 * 表面现象只是"知识库好像没生效",日志里也只有零命中记录。</p>
 */
@Tag("dev")
class ChatRetrievalSupportRelevanceTest {

    private static KnowledgeSearchHitVo hit(Double vectorScore, Double rerankScore, double fusedScore) {
        KnowledgeSearchHitVo vo = new KnowledgeSearchHitVo();
        vo.setChunkId(1L);
        vo.setContent("正文");
        vo.setVectorScore(vectorScore);
        vo.setRerankScore(rerankScore);
        vo.setScore(fusedScore);
        return vo;
    }

    @Test
    void rrfFusedScoreAloneMustNotBeFilteredByAbsoluteThreshold() {
        // 只有 RRF 融合分(0.0164):旧的实现会把它当 0.0164 < 0.05 砍掉,导致生产恒零命中
        KnowledgeSearchHitVo h = hit(null, null, 0.01639);
        assertTrue(ChatRetrievalSupport.passRelevance(h, 0.05),
            "RRF 分不可比,不能被绝对阈值过滤");
    }

    @Test
    void lowCosineIsFiltered() {
        assertFalse(ChatRetrievalSupport.passRelevance(hit(0.01, null, 0.03), 0.05));
        assertTrue(ChatRetrievalSupport.passRelevance(hit(0.42, null, 0.03), 0.05));
    }

    @Test
    void rerankScoreDecidesWhenPresent() {
        assertTrue(ChatRetrievalSupport.passRelevance(hit(0.9, 0.35, 0.03), 0.3));
        assertFalse(ChatRetrievalSupport.passRelevance(hit(0.9, 0.1, 0.03), 0.3),
            "精排分低时即使余弦高也不该通过");
    }

    @Test
    void nonPositiveThresholdKeepsEverything() {
        assertTrue(ChatRetrievalSupport.passRelevance(hit(0.001, 0.001, 0.01), 0));
        assertTrue(ChatRetrievalSupport.passRelevance(hit(-0.5, null, 0.01), 0));
    }

    @Test
    void negativeCosinePassesWhenNoThreshold() {
        assertTrue(ChatRetrievalSupport.passRelevance(hit(-0.9, null, 0.01), 0));
        assertFalse(ChatRetrievalSupport.passRelevance(hit(-0.9, null, 0.01), 0.05));
    }
}
