package com.forgeagent.agent.service.support;

import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.service.retrieve.DegradedReason;
import com.forgeagent.rag.service.retrieve.ScoreType;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.rag.service.retrieve.SearchOutcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 A(A2):检索复用的两块地基——
 * ①免登录 caller 口径路由(客服访客无 Sa-Token,登录口径成员校验必失败);
 * ②预取可复用判定(KB 集合一致才复用,渠道级与 Agent 级 KB 可能不同)。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ChatRetrievalSupportCallerTest {

    @Mock
    private com.forgeagent.rag.service.IAgentKnowledgeService knowledgeService;

    @Mock
    private KnowledgeProperties knowledgeProperties;

    @Mock
    private AgentKbChunkMapper kbChunkMapper;

    @Mock
    private com.forgeagent.agent.config.AgentChatProperties chatProperties;

    @InjectMocks
    private ChatRetrievalSupport support;

    private SearchOutcome outcome(String content) {
        KnowledgeSearchHitVo hit = new KnowledgeSearchHitVo();
        hit.setChunkId(1L);
        hit.setContent(content);
        hit.setScore(0.9);
        return new SearchOutcome(new java.util.ArrayList<>(List.of(hit)),
            new java.util.ArrayList<DegradedReason>(), ScoreType.VECTOR_COSINE, 1, 0, 1L);
    }

    @Test
    void callerWorkspaceIdRoutesToInternalOutcome() {
        when(knowledgeService.searchInternalOutcome(any())).thenReturn(outcome("片段"));
        ChatRetrievalSupport.RetrievalResult result =
            support.retrieveKnowledge(List.of(7L), "查询", null, 9L);
        assertFalse(result.hits().isEmpty());
        verify(knowledgeService).searchInternalOutcome(any(KnowledgeSearchBo.class));
        verify(knowledgeService, never()).searchOutcome(any());
    }

    @Test
    void nullCallerKeepsLoginScopedPath() {
        when(knowledgeService.searchOutcome(any())).thenReturn(outcome("片段"));
        support.retrieveKnowledge(List.of(7L), "查询", null);
        verify(knowledgeService).searchOutcome(any(KnowledgeSearchBo.class));
        verify(knowledgeService, never()).searchInternalOutcome(any());
    }

    @Test
    void prefetchedUsableRequiresIdenticalKbSets() {
        assertTrue(ChatRetrievalSupport.prefetchedUsable(List.of(1L, 2L), List.of(2L, 1L)));
        assertFalse(ChatRetrievalSupport.prefetchedUsable(List.of(1L), List.of(1L, 2L)));
        assertFalse(ChatRetrievalSupport.prefetchedUsable(List.of(), List.of(1L)));
        assertFalse(ChatRetrievalSupport.prefetchedUsable(null, List.of(1L)));
        assertFalse(ChatRetrievalSupport.prefetchedUsable(List.of(1L), List.of()));
    }
}
