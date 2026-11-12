package com.forgeagent.rag.service.graph;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbKgEntity;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.mapper.AgentKbKgEntityMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R5-3 图路排序单测:hop 折扣、同块多实体取 max、hidden 过滤、开关关闭返回空
 *
 * @author ForgeAgent
 */
@Tag("dev")
class KbGraphRetrieverTest {

    @BeforeAll
    static void warmUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            AgentKbKgEntity.class);
    }

    private final KgService kgService = mock(KgService.class);
    private final AgentKnowledgeMapper knowledgeMapper = mock(AgentKnowledgeMapper.class);
    private final AgentKbKgEntityMapper entityMapper = mock(AgentKbKgEntityMapper.class);
    private final KnowledgeProperties properties = new KnowledgeProperties();

    private KbGraphRetriever retriever() {
        properties.setGraphRouteEnabled(true);
        properties.setGraphMaxHops(2);
        properties.setGraphEntityLimit(10);
        properties.setGraphChunkLimit(20);
        AgentKnowledge kb = new AgentKnowledge();
        kb.setId(1L);
        kb.setWorkspaceId(9L);
        when(knowledgeMapper.selectById(1L)).thenReturn(kb);
        when(kgService.enabled()).thenReturn(true);
        return new KbGraphRetriever(kgService, knowledgeMapper, entityMapper, properties);
    }

    private AgentKbKgEntity ref(long chunkId, long docId, String key) {
        AgentKbKgEntity e = new AgentKbKgEntity();
        e.setKnowledgeId(1L);
        e.setDocumentId(docId);
        e.setChunkId(chunkId);
        e.setNodeKey(key);
        e.setNodeName(key);
        return e;
    }

    @Test
    void hopDiscountAndMaxPerChunk() {
        KbGraphRetriever r = retriever();
        when(entityMapper.selectList(any()))
            // 第一次:种子匹配
            .thenReturn(List.of(ref(100, 5, "对象存储")))
            // 第二次:回填分块
            .thenReturn(List.of(
                ref(100, 5, "对象存储"),
                ref(101, 5, "备份策略"),
                ref(101, 5, "对象存储"),
                ref(102, 6, "冷备")));
        // hop1: 对象存储 -> 备份策略;hop2: 备份策略 -> 冷备
        when(kgService.neighbors(any(), anyList(), anyInt()))
            .thenReturn(List.of(KgEdge.of("对象存储", "对象存储", "包含", "备份策略", "备份策略", 1)))
            .thenReturn(List.of(KgEdge.of("备份策略", "备份策略", "包含", "冷备", "冷备", 1)));

        List<Long> chunks = r.retrieve(1L, "对象存储怎么配置", List.of());
        assertEquals(List.of(100L, 101L, 102L), chunks, "按 1/(1+hops) 降序");
    }

    @Test
    void hiddenDocsExcluded() {
        KbGraphRetriever r = retriever();
        when(entityMapper.selectList(any()))
            .thenReturn(List.of(ref(100, 5, "对象存储")))
            .thenReturn(List.of(ref(100, 5, "对象存储"), ref(102, 6, "冷备")));
        when(kgService.neighbors(any(), anyList(), anyInt()))
            .thenReturn(List.of(KgEdge.of("对象存储", "对象存储", "包含", "冷备", "冷备", 1)));
        List<Long> chunks = r.retrieve(1L, "对象存储", List.of(5L));
        assertEquals(List.of(102L), chunks, "文档 5 不可见,只能返回 102");
    }

    @Test
    void disabledSwitchReturnsEmpty() {
        properties.setGraphRouteEnabled(false);
        KbGraphRetriever r = new KbGraphRetriever(kgService, knowledgeMapper, entityMapper, properties);
        assertEquals(List.of(), r.retrieve(1L, "对象存储", List.of()));
    }

    @Test
    void noSeedMatchReturnsEmpty() {
        KbGraphRetriever r = retriever();
        when(entityMapper.selectList(any())).thenReturn(List.of());
        assertEquals(List.of(), r.retrieve(1L, "完全无关的问题", List.of()));
    }
}
