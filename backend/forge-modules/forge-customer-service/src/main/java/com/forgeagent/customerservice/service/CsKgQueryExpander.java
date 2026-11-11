package com.forgeagent.customerservice.service;

import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.kg.service.KgText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图谱辅助检索(M3 后续):把访客文本命中的图谱实体扩展成邻接实体词。
 *
 * <p>访客说「怎么退款」,图谱里有「退款流程 —属于→ 售后领域」,检索 query 就带上
 * 「退款流程 售后领域」——知识库里的答案往往写的是流程名而不是访客的口语,
 * 扩展词能把这两套表述接起来。这是标准 query expansion,只影响检索,
 * 不动 FAQ 确定性匹配、语义缓存键和发给模型的原话。</p>
 *
 * <p>扩展词刻意排除「文本里已有的实体」:它已经在 query 里,再拼一遍只是噪声。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsKgQueryExpander {

    private final KgService kgService;
    private final CsModuleProperties properties;

    /**
     * 扩展检索 query:原文本 + 空格 + 扩展词。开关关闭、图谱不可用或无命中时原样返回。
     */
    public String expandQuery(Long workspaceId, String text) {
        return expandDetailed(workspaceId, text).query();
    }

    /**
     * 扩展结果明细:query 与扩展词一起返回,供调用方区分「本次检索是否带扩展词」
     * (指标埋点对比开/关的零命中率要用这个差别)。
     */
    public Expansion expandDetailed(Long workspaceId, String text) {
        if (StringUtils.isBlank(text)) {
            return new Expansion(text, List.of());
        }
        List<String> terms = expand(workspaceId, text);
        if (terms.isEmpty()) {
            return new Expansion(text, terms);
        }
        return new Expansion(text + " " + String.join(" ", terms), terms);
    }

    /** 一次扩展的产出:最终检索 query + 实际生效的扩展词 */
    public record Expansion(String query, List<String> terms) {
    }

    /** 扩展词列表(去重、排除文本已有实体、按邻接权重顺序截断);无命中返回空列表 */
    List<String> expand(Long workspaceId, String text) {
        if (!Boolean.TRUE.equals(properties.getKgExpandEnabled()) || StringUtils.isBlank(text)) {
            return List.of();
        }
        KgScope scope = KgScope.workspace(workspaceId, null);
        if (!scope.valid() || workspaceId == null || workspaceId < 0) {
            // 空空间哨兵(-1)不查图谱,避免把领域知识挂到不存在的空间
            return List.of();
        }
        List<KgEdge> edges = kgService.neighbors(scope, List.of(text), 12);
        if (edges.isEmpty()) {
            return List.of();
        }
        String flattened = KgText.flatten(text);
        int max = Math.max(1, properties.getKgExpandMaxTerms());
        Set<String> terms = new LinkedHashSet<>();
        outer:
        for (KgEdge edge : edges) {
            for (String name : new String[] {edge.sourceName(), edge.targetName()}) {
                if (terms.size() >= max) {
                    break outer;
                }
                addTerm(terms, flattened, name);
            }
        }
        return List.copyOf(terms);
    }

    /** 扩展词准入:压平后 ≥2 字、且文本里没有这个实体(已在 query 里的词没有增量) */
    private static void addTerm(Set<String> terms, String flattenedText, String name) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        String key = KgText.nameKey(name);
        if (key.length() < 2 || flattenedText.contains(key)) {
            return;
        }
        terms.add(name);
    }
}
