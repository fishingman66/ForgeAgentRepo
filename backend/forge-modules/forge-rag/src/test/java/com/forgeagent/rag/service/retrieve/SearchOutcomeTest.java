package com.forgeagent.rag.service.retrieve;

import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索回执:降级串、首条分数、是否降级。
 */
@Tag("dev")
class SearchOutcomeTest {

    private static KnowledgeSearchHitVo hit(Double vector, Double rerank, double score) {
        KnowledgeSearchHitVo vo = new KnowledgeSearchHitVo();
        vo.setChunkId(1L);
        vo.setContent("x");
        vo.setVectorScore(vector);
        vo.setRerankScore(rerank);
        vo.setScore(score);
        return vo;
    }

    @Test
    void emptyOutcomeIsNotDegraded() {
        SearchOutcome o = SearchOutcome.empty();
        assertTrue(o.hits().isEmpty());
        assertFalse(o.isDegraded());
        assertEquals("", o.degradedCodeText());
        assertNull(o.topScore());
    }

    @Test
    void degradedCodesJoinInOrder() {
        SearchOutcome o = new SearchOutcome(List.of(), List.of(
            DegradedReason.VECTOR_ROUTE_UNAVAILABLE, DegradedReason.RERANK_TIMEOUT), null, 0, 0, 12);
        assertTrue(o.isDegraded());
        assertEquals("vector_route_unavailable,rerank_timeout", o.degradedCodeText());
    }

    @Test
    void topScorePrefersRerankThenVectorThenFused() {
        assertEquals(0.77, new SearchOutcome(List.of(hit(0.5, 0.77, 0.02)), List.of(), null, 1, 0, 5)
            .topScore(), 1e-9);
        assertEquals(0.5, new SearchOutcome(List.of(hit(0.5, null, 0.02)), List.of(), null, 1, 0, 5)
            .topScore(), 1e-9);
        assertEquals(0.02, new SearchOutcome(List.of(hit(null, null, 0.02)), List.of(), null, 0, 1, 5)
            .topScore(), 1e-9);
    }
}
