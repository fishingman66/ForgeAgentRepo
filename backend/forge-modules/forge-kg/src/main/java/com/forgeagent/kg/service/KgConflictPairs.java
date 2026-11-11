package com.forgeagent.kg.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系冲突判定小词表(forge-kg 领域逻辑)。
 *
 * <p>同一 (主语, 宾语) 上出现词表内的反义关系对时,提醒调用方辨析。
 * 记忆域据此在「关联背景」里标注矛盾,客服域可据此做知识冲突告警。
 * 这里只做确定性判定,不做语义推断——那需要另一次模型调用,成本与收益不成比例。</p>
 *
 * @author ForgeAgent
 */
public final class KgConflictPairs {

    /**
     * 反义关系对;按语料里的常见表述收敛。客服领域语料补充了「动作对结果」「状态对状态」
     * 两类:登记领域知识时最常见的冲突就是「这条说解决了、那条说导致了」。
     * 配置扩展见 {@link #isOpposite(String, String, List)}。
     */
    private static final List<Set<String>> OPPOSITES = List.of(
        Set.of("喜欢", "不喜欢"),
        Set.of("偏好", "拒绝"),
        Set.of("偏爱", "排斥"),
        Set.of("使用", "弃用"),
        Set.of("支持", "反对"),
        Set.of("支持", "不支持"),
        Set.of("需要", "不需要"),
        Set.of("负责", "不负责"),
        Set.of("想要", "不想要"),
        Set.of("接受", "拒绝"),
        // 客服领域语料:动作与结果
        Set.of("解决", "导致"),
        Set.of("修复", "引发"),
        Set.of("满足", "违反"),
        // 客服领域语料:开通/状态
        Set.of("开通", "关闭"),
        Set.of("开启", "停用"),
        Set.of("上线", "下线"),
        Set.of("生效", "失效"),
        Set.of("有效", "无效"),
        // 客服领域语料:评价与结果
        Set.of("满意", "不满意"),
        Set.of("成功", "失败"),
        Set.of("同意", "不同意"),
        Set.of("包含", "不含"),
        Set.of("提高", "降低"),
        Set.of("支持", "仅限"));

    private KgConflictPairs() {
    }

    /** 两个关系是否互为反义(只用内置词表) */
    public static boolean isOpposite(String left, String right) {
        return isOpposite(left, right, List.of());
    }

    /**
     * 两个关系是否互为反义;extraPairs 是配置扩展项,每项格式「A:B」,
     * 冒号两侧出现其一且互不相同即判反义。解析失败(缺冒号/空串)的项忽略,不影响内置词表。
     */
    public static boolean isOpposite(String left, String right, List<String> extraPairs) {
        if (left == null || right == null || left.equals(right)) {
            return false;
        }
        String a = left.trim();
        String b = right.trim();
        for (Set<String> pair : OPPOSITES) {
            if (pair.contains(a) && pair.contains(b)) {
                return true;
            }
        }
        if (extraPairs != null) {
            for (String entry : extraPairs) {
                if (entry == null || !entry.contains(":")) {
                    continue;
                }
                int sep = entry.indexOf(':');
                String x = entry.substring(0, sep).trim();
                String y = entry.substring(sep + 1).trim();
                if (x.isEmpty() || y.isEmpty()) {
                    continue;
                }
                if ((x.equals(a) && y.equals(b)) || (x.equals(b) && y.equals(a))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 找出存在相反说法的边,返回需要标注的边标识集合(只用内置词表) */
    public static Set<String> conflictingEdges(List<KgEdge> edges) {
        return conflictingEdges(edges, List.of());
    }

    /** 找出存在相反说法的边;extraPairs 为配置扩展项,格式见 {@link #isOpposite(String, String, List)} */
    public static Set<String> conflictingEdges(List<KgEdge> edges, List<String> extraPairs) {
        Set<String> conflicts = new LinkedHashSet<>();
        if (edges == null || edges.size() < 2) {
            return conflicts;
        }
        Map<String, List<KgEdge>> grouped = new LinkedHashMap<>();
        for (KgEdge edge : edges) {
            grouped.computeIfAbsent(edge.sourceKey() + '\u0001' + edge.targetKey(),
                k -> new ArrayList<>()).add(edge);
        }
        for (List<KgEdge> group : grouped.values()) {
            if (group.size() < 2) {
                continue;
            }
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    if (isOpposite(group.get(i).relation(), group.get(j).relation(), extraPairs)) {
                        conflicts.add(keyOf(group.get(i)));
                        conflicts.add(keyOf(group.get(j)));
                    }
                }
            }
        }
        return conflicts;
    }

    /** 边的稳定标识,供调用方按边标注 */
    public static String keyOf(KgEdge edge) {
        return edge.sourceKey() + '\u0001' + edge.relation() + '\u0001' + edge.targetKey();
    }
}
