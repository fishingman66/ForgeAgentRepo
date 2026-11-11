package com.forgeagent.agent.service.support;

import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.bo.KbQueryLogBo;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.rag.service.retrieve.DegradedReason;
import com.forgeagent.rag.service.retrieve.SearchOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多库检索的合并口径:库内名次 → 跨库 RRF,同名次按 chunkId 破平。
 *
 * <p>回归背景:此前用「除以库内最高分」归一化,每个库的 top1 恒等于 1.0,
 * 排序退化成"库的遍历顺序"——第二个库永远压在第一个库后面,且与内容质量无关。</p>
 */
@Tag("dev")
class ChatRetrievalSupportCrossKbTest {

    private IAgentKnowledgeService knowledgeService;
    private AgentKbChunkMapper chunkMapper;
    private ChatRetrievalSupport support;

    @BeforeEach
    void setUp() {
        knowledgeService = mock(IAgentKnowledgeService.class);
        chunkMapper = mock(AgentKbChunkMapper.class);
        KnowledgeProperties props = new KnowledgeProperties();
        props.setRagTopK(3);
        props.setRagMaxHits(5);
        props.setRagMinScore(0.0);
        // 关掉邻块扩展,避免测试依赖分块查询
        props.setRagNeighborExpand(false);
        props.setCrossKbRrfK(60);
        support = new ChatRetrievalSupport(knowledgeService, props, chunkMapper, new AgentChatProperties());
    }

    private static KnowledgeSearchHitVo hit(long chunkId, double cosine) {
        KnowledgeSearchHitVo vo = new KnowledgeSearchHitVo();
        vo.setChunkId(chunkId);
        vo.setDocumentId(100L);
        vo.setChunkIndex(0);
        vo.setContent("chunk-" + chunkId);
        vo.setVectorScore(cosine);
        vo.setScore(0.016);
        return vo;
    }

    private void stubKb(long kbId, List<KnowledgeSearchHitVo> hits, List<DegradedReason> degraded) {
        when(knowledgeService.searchOutcome(any(KnowledgeSearchBo.class))).thenAnswer(inv -> {
            KnowledgeSearchBo bo = inv.getArgument(0);
            if (bo.getKnowledgeId() != kbId) {
                return new SearchOutcome(List.of(), List.of(), null, 0, 0, 1);
            }
            return new SearchOutcome(hits, degraded, null, hits.size(), 0, 1);
        });
    }

    @Test
    void singleKnowledgeBaseKeepsLibraryOrder() {
        stubKb(1L, List.of(hit(11L, 0.9), hit(12L, 0.8)), List.of());
        ChatRetrievalSupport.RetrievalResult result = support.retrieveKnowledge(List.of(1L), "问题");
        assertEquals(List.of(11L, 12L), result.hits().stream().map(KnowledgeSearchHitVo::getChunkId).toList());
    }

    @Test
    void sameRankAcrossKnowledgeBasesBreaksTieByChunkId() {
        // 两个库各只有一条,名次都是 1 → 同分,按 chunkId 升序,与库顺序无关
        when(knowledgeService.searchOutcome(any(KnowledgeSearchBo.class))).thenAnswer(inv -> {
            KnowledgeSearchBo bo = inv.getArgument(0);
            long id = bo.getKnowledgeId() == 1L ? 9L : 3L;
            return new SearchOutcome(List.of(hit(id, 0.95)), List.of(), null, 1, 0, 1);
        });
        ChatRetrievalSupport.RetrievalResult result = support.retrieveKnowledge(List.of(1L, 2L), "问题");
        assertEquals(List.of(3L, 9L), result.hits().stream().map(KnowledgeSearchHitVo::getChunkId).toList(),
            "同名次应按 chunkId 破平,而不是按知识库遍历顺序");
    }

    @Test
    void ranksInterleaveAcrossKnowledgeBases() {
        // 库1:11(r1) 12(r2);库2:21(r1) 22(r2) → 先两个 r1(11,21),再两个 r2(12,22)
        when(knowledgeService.searchOutcome(any(KnowledgeSearchBo.class))).thenAnswer(inv -> {
            KnowledgeSearchBo bo = inv.getArgument(0);
            List<KnowledgeSearchHitVo> hits = bo.getKnowledgeId() == 1L
                ? List.of(hit(11L, 0.9), hit(12L, 0.8))
                : List.of(hit(21L, 0.7), hit(22L, 0.6));
            return new SearchOutcome(hits, List.of(), null, hits.size(), 0, 1);
        });
        ChatRetrievalSupport.RetrievalResult result = support.retrieveKnowledge(List.of(1L, 2L), "问题");
        assertEquals(List.of(11L, 21L, 12L, 22L),
            result.hits().stream().map(KnowledgeSearchHitVo::getChunkId).toList());
    }

    @Test
    void degradedReasonsFromEachKnowledgeBaseAreAggregated() {
        when(knowledgeService.searchOutcome(any(KnowledgeSearchBo.class))).thenAnswer(inv -> {
            KnowledgeSearchBo bo = inv.getArgument(0);
            if (bo.getKnowledgeId() == 1L) {
                return new SearchOutcome(List.of(hit(11L, 0.9)),
                    List.of(DegradedReason.RERANK_TIMEOUT), null, 1, 0, 1);
            }
            return new SearchOutcome(List.of(hit(21L, 0.8)),
                List.of(DegradedReason.VECTOR_ROUTE_UNAVAILABLE), null, 0, 1, 1);
        });
        support.retrieveKnowledge(List.of(1L, 2L), "问题",
            new ChatRetrievalSupport.RetrievalTrace(7L, 8L, 9L, "原问题"));
        org.mockito.ArgumentCaptor<KbQueryLogBo> captor =
            org.mockito.ArgumentCaptor.forClass(KbQueryLogBo.class);
        verify(knowledgeService).recordQueryLog(captor.capture());
        KbQueryLogBo bo = captor.getValue();
        assertEquals(7L, bo.getAgentId());
        assertEquals("原问题", bo.getOriginalQuery());
        assertEquals("问题", bo.getRetrievalQuery());
        assertEquals(2, bo.getOutcome().degraded().size());
        assertEquals(List.of(11L, 21L),
            bo.getOutcome().hits().stream().map(KnowledgeSearchHitVo::getChunkId).toList());
    }

    @Test
    void noTraceMeansNoQueryLog() {
        stubKb(1L, List.of(hit(11L, 0.9)), List.of());
        support.retrieveKnowledge(List.of(1L), "问题");
        verify(knowledgeService, never()).recordQueryLog(any());
    }

    @Test
    void emptyResultStillLoggedWhenTraceProvided() {
        stubKb(1L, List.of(), List.of(DegradedReason.VECTOR_ROUTE_UNAVAILABLE));
        ChatRetrievalSupport.RetrievalResult result = support.retrieveKnowledge(List.of(1L), "问题",
            new ChatRetrievalSupport.RetrievalTrace(7L, 8L, 9L, "原问题"));
        assertTrue(result.hits().isEmpty());
        org.mockito.ArgumentCaptor<KbQueryLogBo> captor =
            org.mockito.ArgumentCaptor.forClass(KbQueryLogBo.class);
        verify(knowledgeService).recordQueryLog(captor.capture());
        assertTrue(captor.getValue().getOutcome().hits().isEmpty(),
            "零命中也要落日志,否则缺口数据全丢");
    }
}
