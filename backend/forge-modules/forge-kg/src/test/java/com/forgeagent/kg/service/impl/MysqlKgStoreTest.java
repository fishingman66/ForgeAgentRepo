package com.forgeagent.kg.service.impl;

import com.forgeagent.kg.config.KgProperties;
import com.forgeagent.kg.domain.KgEdgeEntity;
import com.forgeagent.kg.domain.KgNodeEntity;
import com.forgeagent.kg.mapper.KgEdgeMapper;
import com.forgeagent.kg.mapper.KgNodeMapper;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgNode;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgText;
import com.forgeagent.kg.service.KgTriple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL 存图实现回归(forge-kg):通用 scope、归一化、自环丢弃、文本反查、去重与权重过滤。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class MysqlKgStoreTest {

    @Mock
    private KgNodeMapper nodeMapper;
    @Mock
    private KgEdgeMapper edgeMapper;

    private KgProperties properties;
    private MysqlKgStore store;

    @BeforeEach
    void setUp() {
        properties = new KgProperties();
        store = new MysqlKgStore(nodeMapper, edgeMapper, properties);
    }

    private static KgNodeEntity node(String key, String name, double salience) {
        KgNodeEntity node = new KgNodeEntity();
        node.setNameKey(key);
        node.setName(name);
        node.setNodeType("entity");
        node.setSalience(BigDecimal.valueOf(salience));
        return node;
    }

    private static KgEdgeEntity edge(String source, String relation, String target, double weight) {
        KgEdgeEntity edge = new KgEdgeEntity();
        edge.setSourceKey(source);
        edge.setRelation(relation);
        edge.setTargetKey(target);
        edge.setWeight(BigDecimal.valueOf(weight));
        return edge;
    }

    @Test
    void edgesListFillsNamesAndKeepsRowId() {
        KgScope scope = KgScope.workspace(88L, null);
        KgEdgeEntity row = edge("退款流程", "属于", "售后领域", 2);
        row.setId(9L);
        when(edgeMapper.selectByScope("workspace", 88L, 0L, 100)).thenReturn(List.of(row));
        when(nodeMapper.selectByScopeAndKeys(eq("workspace"), eq(88L), eq(0L), anyList()))
            .thenReturn(List.of(node("退款流程", "退款流程", 2), node("售后领域", "售后领域", 1)));

        List<KgEdge> edges = store.edges(scope, 100);

        assertEquals(1, edges.size());
        assertEquals(9L, edges.getFirst().id(), "维护页要靠行 ID 删除");
        assertEquals("退款流程", edges.getFirst().sourceName());
        assertEquals("售后领域", edges.getFirst().targetName());
    }

    @Test
    void removeEdgeRejectsForeignScopeAndForeignUser() {
        // 属于其他范围(agent/7)的边,工作空间维护入口删不得
        KgEdgeEntity foreign = edge("用户", "负责", "项目", 2);
        foreign.setId(5L);
        foreign.setScopeType("agent");
        foreign.setScopeId(7L);
        foreign.setUserId(0L);
        when(edgeMapper.selectById(5L)).thenReturn(foreign);
        assertFalse(store.removeEdge(KgScope.workspace(88L, null), 5L),
            "跨范围删除必须拒绝,否则一个空间的维护入口能删掉别的范围的知识");
        verify(edgeMapper, never()).deleteById(anyLong());

        // 同范围但属于个人(user_id=42)的边,共享级维护入口也删不得
        KgEdgeEntity personal = edge("用户", "负责", "项目", 2);
        personal.setId(6L);
        personal.setScopeType("workspace");
        personal.setScopeId(88L);
        personal.setUserId(42L);
        when(edgeMapper.selectById(6L)).thenReturn(personal);
        assertFalse(store.removeEdge(KgScope.workspace(88L, null), 6L),
            "领域知识是共享级的,维护入口删不掉任何个人范围的关系");
        verify(edgeMapper, never()).deleteById(anyLong());
    }

    @Test
    void removeEdgeDeletesWhenScopeMatches() {
        KgEdgeEntity own = edge("退款流程", "属于", "售后领域", 2);
        own.setId(9L);
        own.setScopeType("workspace");
        own.setScopeId(88L);
        own.setUserId(0L);
        when(edgeMapper.selectById(9L)).thenReturn(own);
        when(edgeMapper.deleteById(9L)).thenReturn(1);
        assertTrue(store.removeEdge(KgScope.workspace(88L, null), 9L));
        verify(edgeMapper).deleteById(9L);
    }

    @Test
    void upsertUsesGenericScopeAndSkipsSelfLoop() {
        store.upsert(KgScope.agent(7L, null), List.of(
            new KgTriple("ForgeAgent 项目", "使用", "MySQL", 11L),
            new KgTriple("MySQL", "使用", "mysql。", null)));

        // 共享级 userId 落 0;自环在「压平后主语=宾语」处被丢弃,节点写 2 次、边写 1 次
        verify(nodeMapper, times(2)).upsert(eq("agent"), eq(7L), eq(0L), anyString(),
            anyString(), eq("entity"));
        verify(edgeMapper, times(1)).upsert("agent", 7L, 0L,
            "forgeagent项目", "使用", "mysql", 11L);
    }

    @Test
    void workspaceScopeKeepsMemoryScopeIsolated() {
        store.upsert(KgScope.workspace(88L, null), List.of(
            new KgTriple("退款流程", "属于", "售后领域", 5L)));
        verify(edgeMapper).upsert("workspace", 88L, 0L, "退款流程", "属于", "售后领域", 5L);
    }

    @Test
    void neighborsMatchEntityInTextAndFillBothNames() {
        KgScope scope = KgScope.agent(7L, 42L);
        when(nodeMapper.selectByScope("agent", 7L, 42L, properties.getScanNodeLimit()))
            .thenReturn(List.of(node("forgeagent项目", "ForgeAgent 项目", 3),
                node("mysql", "MySQL", 2)));
        when(edgeMapper.selectAround(eq("agent"), eq(7L), eq(42L), anyList(), eq(5)))
            .thenReturn(List.of(edge("forgeagent项目", "使用", "mysql", 2),
                edge("forgeagent项目", "使用", "mysql", 1)));

        List<KgEdge> edges = store.neighbors(scope,
            List.of("我在做 ForgeAgent 项目的后端,数据库用 MySQL"), 5);

        assertEquals(1, edges.size(), "本人与共享级同构边应去重");
        KgEdge only = edges.getFirst();
        assertEquals("ForgeAgent 项目", only.sourceName());
        assertEquals("MySQL", only.targetName());
        assertEquals("使用", only.relation());
    }

    @Test
    void neighborsIgnoreEntityWhenTextHasNoMention() {
        when(nodeMapper.selectByScope("agent", 7L, 42L, properties.getScanNodeLimit()))
            .thenReturn(List.of(node("forgeagent项目", "ForgeAgent 项目", 3)));
        assertTrue(store.neighbors(KgScope.agent(7L, 42L), List.of("今天天气不错"), 5).isEmpty());
    }

    @Test
    void weakEdgesAreFilteredByMinWeight() {
        properties.setMinEdgeWeight(2.0);
        when(nodeMapper.selectByScope("agent", 7L, 42L, properties.getScanNodeLimit()))
            .thenReturn(List.of(node("forgeagent项目", "ForgeAgent 项目", 3)));
        when(edgeMapper.selectAround(eq("agent"), eq(7L), eq(42L), anyList(), eq(5)))
            .thenReturn(List.of(edge("forgeagent项目", "使用", "mysql", 1)));
        assertTrue(store.neighbors(KgScope.agent(7L, 42L), List.of("ForgeAgent 项目"), 5).isEmpty());
    }

    @Test
    void nodesMapsSalience() {
        when(nodeMapper.selectByScope("workspace", 88L, 0L, 10))
            .thenReturn(List.of(node("退款流程", "退款流程", 4)));
        List<KgNode> nodes = store.nodes(KgScope.workspace(88L, null), 10);
        assertEquals(1, nodes.size());
        assertEquals("退款流程", nodes.getFirst().name());
        assertEquals(4.0, nodes.getFirst().salience());
    }

    @Test
    void deleteScopeClearsEdgesBeforeNodes() {
        store.deleteScope(KgScope.agent(7L, 42L));
        verify(edgeMapper).deleteByScope("agent", 7L, 42L);
        verify(nodeMapper).deleteByScope("agent", 7L, 42L);
    }

    @Test
    void graphTextNormalizationIsStable() {
        assertEquals("forgeagent项目", KgText.nameKey("  ForgeAgent 项目。 "));
        assertEquals("forgeagent项目", KgText.flatten("ForgeAgent 项目!"));
        assertEquals("使用", KgText.relation("使用,"));
        assertEquals("", KgText.nameKey(" 。 "));
    }
}
