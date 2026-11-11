package com.forgeagent.kg.service.impl;

import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.kg.config.KgProperties;
import com.forgeagent.kg.domain.KgEdgeEntity;
import com.forgeagent.kg.domain.KgNodeEntity;
import com.forgeagent.kg.mapper.KgEdgeMapper;
import com.forgeagent.kg.mapper.KgNodeMapper;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgNode;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgStore;
import com.forgeagent.kg.service.KgText;
import com.forgeagent.kg.service.KgTriple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 MySQL 两表的图谱存储实现(forge-kg,开发与测试环境默认后端)。
 *
 * <p>不引入 Neo4j:单域的图规模(几百节点、几千边)用两表 + 1 跳自关联 SQL 足够,
 * 且不需要新的运维组件。读写全部由 {@link KgStore} 收口,
 * 生产规模上来时新增 Neo4j 实现即可替换,本类与调用方都不用改。</p>
 *
 * <p>{@link #neighbors} 的匹配策略是「文本包含实体名」:先把输入压平成
 * 去标点小写串,再与同样压平的实体唯一键做包含判断。这里刻意不做分词,
 * 因为实体名本身就是抽取出来的短词,包含匹配在中文里足够用且零依赖。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MysqlKgStore implements KgStore {

    /** 后端标识,与 {@code kg.backend} 对应 */
    public static final String BACKEND = "mysql";

    /** 实体类型暂统一为 entity,类型细分等到有真实检索需求再做 */
    private static final String DEFAULT_NODE_TYPE = "entity";

    /** 单次最多用多少个实体反查邻接边,防止压平文本里命中过多短实体把 SQL 撑大 */
    private static final int MAX_MATCH_KEYS = 8;

    /** 最短实体名(压平后),过短的 key 在中文里几乎必然误命中 */
    private static final int MIN_KEY_LENGTH = 2;

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final KgProperties properties;

    @Override
    public String backend() {
        return BACKEND;
    }

    @Override
    public void upsert(KgScope scope, List<KgTriple> triples) {
        if (scope == null || !scope.valid() || triples == null || triples.isEmpty()) {
            return;
        }
        long userId = scope.userIdValue();
        for (KgTriple triple : triples) {
            if (triple == null) {
                continue;
            }
            String sourceKey = KgText.nameKey(triple.subject());
            String targetKey = KgText.nameKey(triple.object());
            String relation = KgText.relation(triple.relation());
            // 主语=宾语的自环、空实体、空关系一律丢弃
            if (sourceKey.isEmpty() || targetKey.isEmpty() || relation.isEmpty()
                || sourceKey.equals(targetKey)) {
                continue;
            }
            nodeMapper.upsert(scope.scopeType(), scope.scopeId(), userId, sourceKey,
                KgText.clean(triple.subject()), DEFAULT_NODE_TYPE);
            nodeMapper.upsert(scope.scopeType(), scope.scopeId(), userId, targetKey,
                KgText.clean(triple.object()), DEFAULT_NODE_TYPE);
            edgeMapper.upsert(scope.scopeType(), scope.scopeId(), userId, sourceKey, relation,
                targetKey, triple.evidenceRef());
        }
    }

    @Override
    public List<KgEdge> neighbors(KgScope scope, List<String> texts, int limit) {
        if (scope == null || !scope.valid() || limit <= 0 || texts == null || texts.isEmpty()) {
            return List.of();
        }
        String type = scope.scopeType();
        Long scopeId = scope.scopeId();
        long userId = scope.userIdValue();
        List<KgNodeEntity> nodes = nodeMapper.selectByScope(type, scopeId, userId,
            Math.max(1, properties.getScanNodeLimit()));
        if (nodes.isEmpty()) {
            return List.of();
        }
        String haystack = flattenAll(texts);
        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (KgNodeEntity node : nodes) {
            String key = node.getNameKey();
            if (StringUtils.isBlank(key) || key.length() < MIN_KEY_LENGTH
                || StringUtils.isBlank(node.getName())) {
                continue;
            }
            if (haystack.contains(key)) {
                nameByKey.put(key, node.getName());
                if (nameByKey.size() >= MAX_MATCH_KEYS) {
                    break;
                }
            }
        }
        if (nameByKey.isEmpty()) {
            return List.of();
        }
        List<KgEdgeEntity> edges = edgeMapper.selectAround(type, scopeId, userId,
            new ArrayList<>(nameByKey.keySet()), limit);
        if (edges.isEmpty()) {
            return List.of();
        }
        Set<String> missing = new LinkedHashSet<>();
        for (KgEdgeEntity edge : edges) {
            if (!nameByKey.containsKey(edge.getSourceKey())) {
                missing.add(edge.getSourceKey());
            }
            if (!nameByKey.containsKey(edge.getTargetKey())) {
                missing.add(edge.getTargetKey());
            }
        }
        if (!missing.isEmpty()) {
            List<KgNodeEntity> extra = nodeMapper.selectByScopeAndKeys(
                type, scopeId, userId, new ArrayList<>(missing));
            extra.forEach(node -> nameByKey.putIfAbsent(node.getNameKey(), node.getName()));
        }
        double minWeight = properties.getMinEdgeWeight();
        Set<String> seen = new LinkedHashSet<>();
        List<KgEdge> result = new ArrayList<>(edges.size());
        for (KgEdgeEntity edge : edges) {
            double weight = edge.getWeight() == null ? 0 : edge.getWeight().doubleValue();
            if (weight < minWeight) {
                continue;
            }
            if (!seen.add(edge.getSourceKey() + '\u0001' + edge.getRelation() + '\u0001'
                + edge.getTargetKey())) {
                continue;
            }
            result.add(KgEdge.of(edge.getSourceKey(), nameOf(nameByKey, edge.getSourceKey()),
                edge.getRelation(), edge.getTargetKey(), nameOf(nameByKey, edge.getTargetKey()),
                weight));
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    @Override
    public List<KgNode> nodes(KgScope scope, int limit) {
        if (scope == null || !scope.valid() || limit <= 0) {
            return List.of();
        }
        List<KgNodeEntity> rows = nodeMapper.selectByScope(
            scope.scopeType(), scope.scopeId(), scope.userIdValue(), limit);
        List<KgNode> nodes = new ArrayList<>(rows.size());
        for (KgNodeEntity row : rows) {
            nodes.add(new KgNode(row.getNameKey(), row.getName(), row.getNodeType(),
                row.getSalience() == null ? 0 : row.getSalience().doubleValue()));
        }
        return nodes;
    }

    @Override
    public List<KgEdge> edges(KgScope scope, int limit) {
        if (scope == null || !scope.valid() || limit <= 0) {
            return List.of();
        }
        List<KgEdgeEntity> rows = edgeMapper.selectByScope(
            scope.scopeType(), scope.scopeId(), scope.userIdValue(), limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        // 两端展示名统一从节点表补:维护页要给运营看名字,不是小写键
        Set<String> endpointKeys = new LinkedHashSet<>();
        for (KgEdgeEntity row : rows) {
            endpointKeys.add(row.getSourceKey());
            endpointKeys.add(row.getTargetKey());
        }
        Map<String, String> nameByKey = new LinkedHashMap<>();
        nodeMapper.selectByScopeAndKeys(scope.scopeType(), scope.scopeId(), scope.userIdValue(),
            new ArrayList<>(endpointKeys))
            .forEach(node -> nameByKey.put(node.getNameKey(), node.getName()));
        List<KgEdge> edges = new ArrayList<>(rows.size());
        for (KgEdgeEntity row : rows) {
            edges.add(new KgEdge(row.getId(),
                row.getSourceKey(), nameOf(nameByKey, row.getSourceKey()),
                row.getRelation(), row.getTargetKey(), nameOf(nameByKey, row.getTargetKey()),
                row.getWeight() == null ? 0 : row.getWeight().doubleValue()));
        }
        return edges;
    }

    @Override
    public boolean removeEdge(KgScope scope, Long edgeId) {
        if (scope == null || !scope.valid() || edgeId == null) {
            return false;
        }
        KgEdgeEntity edge = edgeMapper.selectById(edgeId);
        if (edge == null || !scope.scopeType().equals(edge.getScopeType())
            || !scope.scopeId().equals(edge.getScopeId())
            || edge.getUserId() == null || edge.getUserId() != scope.userIdValue()) {
            // 范围/共享级对不上时拒绝删除,防止维护入口误删其他范围或其他用户的关系
            return false;
        }
        return edgeMapper.deleteById(edgeId) > 0;
    }

    @Override
    public void deleteScope(KgScope scope) {
        if (scope == null || !scope.valid()) {
            return;
        }
        edgeMapper.deleteByScope(scope.scopeType(), scope.scopeId(), scope.userIdValue());
        nodeMapper.deleteByScope(scope.scopeType(), scope.scopeId(), scope.userIdValue());
    }

    private static String flattenAll(List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (String text : texts) {
            if (StringUtils.isNotBlank(text)) {
                sb.append(KgText.flatten(text)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String nameOf(Map<String, String> nameByKey, String key) {
        String name = nameByKey.get(key);
        return StringUtils.isBlank(name) ? key : name;
    }
}
