package com.forgeagent.memory.service.impl;

import com.forgeagent.kg.service.KgConflictPairs;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.memory.config.MemoryGraphProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 记忆侧的「图谱注入」适配层(P3 / M3)。
 *
 * <p>图谱的存取、归一化、冲突判定都在 {@code forge-kg};本类只负责把一个通用
 * {@link KgEdge} 列表翻译成模型提示词里的「关联背景」小段,以及管理记忆域自己的
 * 字符预算。客服域将来拿同一份 {@code KgService} 输出的是结构化关系,不会共用这段文案。</p>
 *
 * @author ForgeAgent
 */
@Service
@RequiredArgsConstructor
public class MemoryGraphInjection {

    private final KgService kgService;
    private final MemoryGraphProperties properties;

    /** 图谱总开关来自 forge-kg(kg.enabled + 后端实现存在) */
    public boolean enabled() {
        return kgService.enabled();
    }

    /** 「关联背景」占用的字符预算;关闭时为 0 */
    public int reserveChars() {
        return enabled() ? Math.max(0, properties.getInjectChars()) : 0;
    }

    public int rebuildBatch() {
        return Math.max(1, properties.getRebuildBatch());
    }

    public int rebuildLimit() {
        return Math.max(1, properties.getRebuildLimit());
    }

    /**
     * 构建关联背景:用入选记忆文本反查实体,展开 1 跳邻接关系。
     *
     * @param maxChars 从记忆块总预算里扣出来的字符额度
     */
    public String build(KgScope scope, List<String> texts, int maxChars) {
        if (maxChars <= 0 || scope == null || !scope.valid()
            || texts == null || texts.isEmpty()) {
            return "";
        }
        List<KgEdge> edges = kgService.neighbors(scope, texts,
            Math.max(1, properties.getNeighborLimit()));
        return render(edges, kgService.conflicts(edges), maxChars);
    }

    /**
     * 渲染关联背景。追加在记忆块末尾:前面是 factKey 稳定排序的事实条目,
     * 动态内容放尾部不会让跨轮前缀整体位移,供应商侧 KV Cache 仍可命中。
     */
    static String render(List<KgEdge> edges, Set<String> conflicts, int maxChars) {
        if (edges == null || edges.isEmpty() || maxChars <= 0) {
            return "";
        }
        String header = "[关联背景]\n以下是这些记忆之间的已知关联,回答时可用于补充背景,不必原样复述:\n";
        if (header.length() >= maxChars) {
            return "";
        }
        StringBuilder sb = new StringBuilder(header);
        int added = 0;
        for (KgEdge edge : edges) {
            String line = edge.sourceName() + " —" + edge.relation() + "→ " + edge.targetName();
            if (conflicts != null && conflicts.contains(KgConflictPairs.keyOf(edge))) {
                line = "注意(与另一条记忆说法相反,以较新者为准):" + line;
            }
            if (sb.length() + line.length() + 1 > maxChars) {
                break;
            }
            sb.append(line).append('\n');
            added++;
        }
        return added == 0 ? "" : sb.toString().trim();
    }
}
