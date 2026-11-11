package com.forgeagent.memory.service.impl;

import com.forgeagent.kg.service.KgConflictPairs;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.memory.config.MemoryGraphProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆侧图谱注入适配层回归(P3 / M3):渲染、冲突标注、预算、开关与客服域隔离。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class MemoryGraphInjectionTest {

    @Mock
    private KgService kgService;

    private MemoryGraphProperties properties;
    private MemoryGraphInjection injection;

    @BeforeEach
    void setUp() {
        properties = new MemoryGraphProperties();
        injection = new MemoryGraphInjection(kgService, properties);
    }

    private static KgEdge edge(String source, String relation, String target, double weight) {
        return KgEdge.of(source, source, relation, target, target, weight);
    }

    @Test
    void reserveCharsFollowsKgMasterSwitch() {
        when(kgService.enabled()).thenReturn(false);
        assertEquals(0, injection.reserveChars());
        assertTrue(injection.build(KgScope.agent(7L, 42L), List.of("文本"), 200).isEmpty());
    }

    @Test
    void buildUsesAgentScopeAndConfiguredNeighborLimit() {
        when(kgService.enabled()).thenReturn(true);
        when(kgService.neighbors(any(), anyList(), anyInt())).thenReturn(List.of());
        when(kgService.conflicts(anyList())).thenReturn(Set.of());

        assertEquals(properties.getInjectChars(), injection.reserveChars());
        injection.build(KgScope.agent(7L, 42L), List.of("项目"), 200);

        verify(kgService).neighbors(eq(KgScope.agent(7L, 42L)), anyList(),
            eq(properties.getNeighborLimit()));
    }

    @Test
    void renderListsRelationsWithinBudget() {
        List<KgEdge> edges = List.of(
            edge("用户", "负责", "ForgeAgent 项目", 3),
            edge("ForgeAgent 项目", "使用", "MySQL", 2));
        String block = MemoryGraphInjection.render(edges, Set.of(), 300);
        assertTrue(block.startsWith("[关联背景]"));
        assertTrue(block.contains("用户 —负责→ ForgeAgent 项目"));
        assertTrue(block.contains("ForgeAgent 项目 —使用→ MySQL"));
        assertTrue(block.length() <= 300);
    }

    @Test
    void renderDropsEverythingWhenBudgetTooSmall() {
        List<KgEdge> edges = List.of(edge("用户", "负责", "项目", 3));
        assertTrue(MemoryGraphInjection.render(edges, Set.of(), 20).isEmpty());
        assertTrue(MemoryGraphInjection.render(List.of(), Set.of(), 300).isEmpty());
    }

    @Test
    void renderMarksOppositeRelations() {
        KgEdge like = KgEdge.of("用户", "用户", "喜欢", "简洁回复", "简洁回复", 3);
        KgEdge dislike = KgEdge.of("用户", "用户", "不喜欢", "简洁回复", "简洁回复", 2);
        Set<String> conflicts = KgConflictPairs.conflictingEdges(List.of(like, dislike));

        String block = MemoryGraphInjection.render(List.of(like, dislike), conflicts, 400);
        int first = block.indexOf("说法相反");
        assertTrue(first > 0, "相反关系应显式标注");
        assertTrue(block.indexOf("说法相反", first + 1) > 0, "矛盾双方都要标出来");
    }
}
