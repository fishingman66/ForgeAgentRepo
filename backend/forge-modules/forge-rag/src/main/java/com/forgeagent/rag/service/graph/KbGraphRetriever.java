package com.forgeagent.rag.service.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.kg.service.KgText;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbKgEntity;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.mapper.AgentKbKgEntityMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R5-3 图谱辅助检索(第三路):实体匹配 → 沿关系扩 ≤2 跳 → 回落到来源分块。
 *
 * <p>相关性口径:{@code 1 / (1 + hops)};同一分块多实体<b>取 max 不求和</b>
 * (否则"提到得多"的长块会获胜)。返回的 chunkId 仍要经过上层
 * {@code loadChunksAndDocs} 的 MySQL 复核(enabled=1 + 当前 generation),图是滞后数据。</p>
 *
 * <p>融合只允许 RRF:hop 折扣分与向量余弦/BM25 是不同尺度的量,没有可用权重。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class KbGraphRetriever {

    private final KgService kgService;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentKbKgEntityMapper entityMapper;
    private final KnowledgeProperties properties;

    /**
     * @param hiddenDocIds 不可见文档(ACL/治理),图路同样不能召回
     * @return 按图相关度降序的 chunkId(已去重,数量受 graph-chunk-limit 约束)
     */
    public List<Long> retrieve(Long knowledgeId, String query, List<Long> hiddenDocIds) {
        if (!Boolean.TRUE.equals(properties.getGraphRouteEnabled()) || !kgService.enabled()) {
            return List.of();
        }
        List<String> tokens = GraphQueryTokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        AgentKnowledge kb = knowledgeMapper.selectById(knowledgeId);
        if (kb == null) {
            return List.of();
        }
        KgScope scope = KgScope.workspace(kb.getWorkspaceId() == null ? 0L : kb.getWorkspaceId(), 0L);
        // 1) 实体匹配:token 与映射表 node_key / node_name 精确对齐(归一化后)
        Set<String> tokenKeys = new LinkedHashSet<>();
        for (String token : tokens) {
            String key = KgText.nameKey(token);
            if (!key.isEmpty()) {
                tokenKeys.add(key);
            }
        }
        if (tokenKeys.isEmpty()) {
            return List.of();
        }
        List<AgentKbKgEntity> seeds = entityMapper.selectList(new LambdaQueryWrapper<AgentKbKgEntity>()
            .select(AgentKbKgEntity::getNodeKey, AgentKbKgEntity::getNodeName)
            .eq(AgentKbKgEntity::getKnowledgeId, knowledgeId)
            .in(AgentKbKgEntity::getNodeKey, tokenKeys)
            .last("limit " + Math.max(1, properties.getGraphEntityLimit() * 5)));
        if (seeds.isEmpty()) {
            return List.of();
        }
        Map<String, String> seedNames = new LinkedHashMap<>();
        for (AgentKbKgEntity seed : seeds) {
            seedNames.putIfAbsent(seed.getNodeKey(), seed.getNodeName());
            if (seedNames.size() >= Math.max(1, properties.getGraphEntityLimit())) {
                break;
            }
        }

        // 2) 扩跳:hop1 = 种子邻居,hop2 = hop1 的邻居(受总跳数限制;每跳各一次图查询)
        Map<String, Integer> hopOf = new HashMap<>();
        seedNames.keySet().forEach(k -> hopOf.put(k, 0));
        int maxHops = Math.max(1, properties.getGraphMaxHops());
        for (int hop = 1; hop <= maxHops; hop++) {
            List<String> frontierNames = namesAtHop(hopOf, hop - 1);
            if (frontierNames.isEmpty()) {
                break;
            }
            List<KgEdge> edges = kgService.neighbors(scope, frontierNames,
                Math.max(20, properties.getGraphEntityLimit() * 10));
            for (KgEdge edge : edges) {
                String sk = edge.sourceKey();
                String tk = edge.targetKey();
                if (!hopOf.containsKey(sk)) {
                    hopOf.put(sk, hop);
                }
                if (!hopOf.containsKey(tk)) {
                    hopOf.put(tk, hop);
                }
            }
        }
        if (hopOf.isEmpty()) {
            return List.of();
        }

        // 3) 回落到分块:同分块多实体取 max,再按相关度降序
        List<AgentKbKgEntity> refs = entityMapper.selectList(new LambdaQueryWrapper<AgentKbKgEntity>()
            .eq(AgentKbKgEntity::getKnowledgeId, knowledgeId)
            .in(AgentKbKgEntity::getNodeKey, hopOf.keySet())
            .last("limit 2000"));
        Set<Long> hidden = hiddenDocIds == null ? Set.of() : new LinkedHashSet<>(hiddenDocIds);
        Map<Long, Double> scoreByChunk = new HashMap<>();
        for (AgentKbKgEntity ref : refs) {
            if (ref.getDocumentId() != null && hidden.contains(ref.getDocumentId())) {
                continue;
            }
            Integer hops = hopOf.get(ref.getNodeKey());
            if (hops == null) {
                continue;
            }
            double score = 1.0 / (1 + hops);
            scoreByChunk.merge(ref.getChunkId(), score, Math::max);
        }
        int limit = Math.max(1, properties.getGraphChunkLimit());
        List<Long> chunks = new ArrayList<>(scoreByChunk.keySet());
        chunks.sort(Comparator
            .comparingDouble((Long id) -> scoreByChunk.getOrDefault(id, 0.0)).reversed()
            .thenComparing(Comparator.naturalOrder()));
        return chunks.size() > limit ? chunks.subList(0, limit) : chunks;
    }

    /**
     * 取指定跳数层上的实体展示名(供下一次 neighbors 查询)
     */
    private List<String> namesAtHop(Map<String, Integer> hopOf, int hop) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Integer> e : hopOf.entrySet()) {
            if (e.getValue() == hop) {
                names.add(e.getKey());
            }
        }
        return names;
    }
}
