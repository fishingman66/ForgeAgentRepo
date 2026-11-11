package com.forgeagent.customerservice.service;

import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsKgRelation;
import com.forgeagent.customerservice.domain.CsKgRelationVo;
import com.forgeagent.kg.service.KgConflictPairs;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgNode;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.kg.service.KgTriple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客服领域知识图谱维护(P3 / M3):领域三元组的登记、盘点与清理。
 *
 * <p>图谱本体在 forge-kg,本服务只做三件事:把坐席所属空间解析成
 * {@link KgScope#workspace},把受控录入的三元组交给 {@code KgService.ingest},
 * 把返回的边翻译成带冲突标注的展示行。客服域不需要(也不应该)绕过 KgService
 * 直接碰存储——否则换 Neo4j 时客服要跟着改。</p>
 *
 * <p>范围统一用共享级(userId=null):领域知识归空间所有,不挂到具体坐席;
 * 反过来,维护入口也因此删不掉任何个人(user_id≠0)范围的关系。</p>
 *
 * @author ForgeAgent
 */
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsKnowledgeGraphService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final KgService kgService;
    private final CsWorkspaceResolver workspaceResolver;
    private final CsKgExpandMetricService kgExpandMetricService;

    /** 领域关系列表(按权重降序),带行 ID 与冲突标注 */
    public List<CsKgRelationVo> list(Integer limit) {
        KgScope scope = scope();
        List<KgEdge> edges = kgService.edges(scope, normalizeLimit(limit));
        Set<String> conflicts = kgService.conflicts(edges);
        List<CsKgRelationVo> result = new ArrayList<>(edges.size());
        for (KgEdge edge : edges) {
            CsKgRelationVo vo = toVo(edge);
            vo.setConflict(conflicts.contains(KgConflictPairs.keyOf(edge)));
            result.add(vo);
        }
        return result;
    }

    /**
     * 冲突盘点:同一实体对上说法相反的关系,两两一组返回。
     * 「审核」的动作就是人工判断后删掉错的那条(复用现有删除端点),不引入新状态表。
     */
    public List<List<CsKgRelationVo>> conflicts(Integer limit) {
        KgScope scope = scope();
        List<KgEdge> edges = kgService.edges(scope, normalizeLimit(limit));
        Set<String> conflictKeys = kgService.conflicts(edges);
        if (conflictKeys.isEmpty()) {
            return List.of();
        }
        Map<String, List<KgEdge>> grouped = new LinkedHashMap<>();
        for (KgEdge edge : edges) {
            if (conflictKeys.contains(KgConflictPairs.keyOf(edge))) {
                grouped.computeIfAbsent(edge.sourceKey() + '\u0001' + edge.targetKey(),
                    k -> new ArrayList<>()).add(edge);
            }
        }
        List<List<CsKgRelationVo>> groups = new ArrayList<>();
        for (List<KgEdge> group : grouped.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    if (KgConflictPairs.isOpposite(group.get(i).relation(), group.get(j).relation())) {
                        CsKgRelationVo left = toVo(group.get(i));
                        CsKgRelationVo right = toVo(group.get(j));
                        left.setConflict(true);
                        right.setConflict(true);
                        groups.add(List.of(left, right));
                    }
                }
            }
        }
        return groups;
    }

    /** 手工登记领域三元组;图谱未启用直接报错,不做「看起来成功」的假确认 */
    public Boolean add(CsKgRelation relation) {
        KgScope scope = requireEnabled();
        kgService.ingest(scope, List.of(new KgTriple(
            relation.getSubject(), relation.getRelation(), relation.getObject(),
            relation.getEvidenceRef())));
        return true;
    }

    /** 删除一条领域关系;范围对不上或行不存在返回 false(前端提示重新刷新列表) */
    public Boolean remove(Long id) {
        return kgService.removeEdge(scope(), id);
    }

    /** 空间内实体 TOP(维护页选实体时联想用) */
    public List<KgNode> nodes(Integer limit) {
        return kgService.nodes(scope(), normalizeLimit(limit));
    }

    /** 检索扩展效果:最近 N 天日聚合行,零命中率由前端按行计算对比 */
    public List<java.util.Map<String, Object>> listExpandMetrics(Integer days) {
        return kgExpandMetricService.list(workspaceResolver.currentWorkspaceId(), days);
    }

    private static CsKgRelationVo toVo(KgEdge edge) {
        CsKgRelationVo vo = new CsKgRelationVo();
        vo.setId(edge.id());
        vo.setSubject(edge.sourceName());
        vo.setRelation(edge.relation());
        vo.setObject(edge.targetName());
        vo.setWeight(java.math.BigDecimal.valueOf(edge.weight()));
        return vo;
    }

    private KgScope scope() {
        return KgScope.workspace(workspaceResolver.currentWorkspaceId(), null);
    }

    private KgScope requireEnabled() {
        KgScope scope = scope();
        if (!kgService.enabled()) {
            throw new ServiceException("知识图谱未启用,请检查 kg.enabled 与 kg.backend 配置");
        }
        return scope;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
