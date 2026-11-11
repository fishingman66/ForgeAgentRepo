package com.forgeagent.customerservice.service;

import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.domain.CsKgRelation;
import com.forgeagent.customerservice.domain.CsKgRelationVo;
import com.forgeagent.kg.service.KgConflictPairs;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.kg.service.KgTriple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客服领域三元组维护回归(P3 / M3):空间 scope、冲突标注、关闭即报错、删除走共享级。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsKnowledgeGraphServiceTest {

    private static final KgScope WS = KgScope.workspace(88L, null);

    @Mock
    private KgService kgService;
    @Mock
    private CsWorkspaceResolver workspaceResolver;
    @Mock
    private CsKgExpandMetricService kgExpandMetricService;

    private CsKnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        service = new CsKnowledgeGraphService(kgService, workspaceResolver, kgExpandMetricService);
    }

    @Test
    void listMarksConflictsAndKeepsRowId() {
        when(workspaceResolver.currentWorkspaceId()).thenReturn(88L);
        KgEdge like = new KgEdge(9L, "用户", "用户", "喜欢", "简洁回复", "简洁回复", 3);
        KgEdge dislike = new KgEdge(10L, "用户", "用户", "不喜欢", "简洁回复", "简洁回复", 2);
        when(kgService.edges(WS, 100)).thenReturn(List.of(like, dislike));
        when(kgService.conflicts(anyList()))
            .thenReturn(KgConflictPairs.conflictingEdges(List.of(like, dislike)));

        List<CsKgRelationVo> rows = service.list(null);

        assertEquals(2, rows.size());
        assertEquals(9L, rows.getFirst().getId(), "行 ID 要透出,维护页删除要用");
        assertTrue(rows.getFirst().isConflict());
        assertTrue(rows.get(1).isConflict(), "矛盾双方都要标注");
    }

    @Test
    void addRejectsWhenGraphDisabled() {
        when(kgService.enabled()).thenReturn(false);
        CsKgRelation bo = relation();

        ServiceException ex = assertThrows(ServiceException.class, () -> service.add(bo));
        assertTrue(ex.getMessage().contains("知识图谱未启用"),
            "关闭时不能静默假成功,否则运营会以为领域知识登记上了");
        verify(kgService, never()).ingest(any(), anyList());
    }

    @Test
    void addIngestsWithWorkspaceSharedScopeAndEvidence() {
        when(workspaceResolver.currentWorkspaceId()).thenReturn(88L);
        when(kgService.enabled()).thenReturn(true);

        assertTrue(service.add(relation()));

        ArgumentCaptor<List<KgTriple>> captor = ArgumentCaptor.forClass(List.class);
        verify(kgService).ingest(eq(WS), captor.capture());
        assertEquals(List.of(new KgTriple("退款流程", "属于", "售后领域", 77L)),
            captor.getValue(), "三元组要落在空间共享级,证据引用透传");
    }

    @Test
    void removeTargetsWorkspaceSharedScope() {
        when(workspaceResolver.currentWorkspaceId()).thenReturn(88L);
        when(kgService.removeEdge(WS, 9L)).thenReturn(true);
        assertTrue(service.remove(9L));
    }

    @Test
    void conflictsGroupOppositePairsForReview() {
        when(workspaceResolver.currentWorkspaceId()).thenReturn(88L);
        KgEdge like = KgEdge.of("用户", "用户", "喜欢", "简洁回复", "简洁回复", 3);
        KgEdge dislike = KgEdge.of("用户", "用户", "不喜欢", "简洁回复", "简洁回复", 2);
        KgEdge normal = KgEdge.of("客服机器人", "客服机器人", "解答", "退款问题", "退款问题", 1);
        when(kgService.edges(WS, 100)).thenReturn(List.of(like, dislike, normal));
        when(kgService.conflicts(anyList()))
            .thenReturn(KgConflictPairs.conflictingEdges(List.of(like, dislike)));

        List<List<CsKgRelationVo>> groups = service.conflicts(null);

        assertEquals(1, groups.size(), "只有反义对成组,无冲突的正常关系不进盘点");
        assertEquals(2, groups.getFirst().size());
        assertTrue(groups.getFirst().stream().allMatch(CsKgRelationVo::isConflict));
        assertEquals("喜欢", groups.getFirst().get(0).getRelation());
        assertEquals("不喜欢", groups.getFirst().get(1).getRelation());
    }

    @Test
    void conflictsEmptyWhenNone() {
        when(workspaceResolver.currentWorkspaceId()).thenReturn(88L);
        when(kgService.edges(WS, 100)).thenReturn(List.of());
        assertTrue(service.conflicts(null).isEmpty());
    }

    @Test
    void expandMetricsDelegateToSeatWorkspace() {
        when(workspaceResolver.currentWorkspaceId()).thenReturn(88L);
        when(kgExpandMetricService.list(88L, 30)).thenReturn(List.of(
            java.util.Map.of("statDate", "2026-11-10", "totalSearches", 10L)));
        assertEquals(1, service.listExpandMetrics(30).size());
    }

    private static CsKgRelation relation() {
        CsKgRelation bo = new CsKgRelation();
        bo.setSubject("退款流程");
        bo.setRelation("属于");
        bo.setObject("售后领域");
        bo.setEvidenceRef(77L);
        return bo;
    }
}
