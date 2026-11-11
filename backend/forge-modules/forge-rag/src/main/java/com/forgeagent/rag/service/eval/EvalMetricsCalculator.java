package com.forgeagent.rag.service.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * R3-3 五指标计算(纯静态,零业务依赖)。
 *
 * <p>HitRate@K / Recall@K / Precision@K / MRR / NDCG@K + Wilson 95% 置信区间。
 * 置信区间仅展示,永不参与门禁(门禁噪声控制只用 ε,禁止两套机制并存)。</p>
 *
 * <p>分组:overall / span(带证据 span 的用例) / document(仅文档级证据) /
 * legacy(旧口径用例)。单轮/多轮分组在检索评测里没有字段支撑,留给 agent_eval。
 * 空组省略而非填 0 —— 区分"没有这类用例"与"这类得 0 分"。</p>
 *
 * @author ForgeAgent
 */
public final class EvalMetricsCalculator {

    private static final double WILSON_Z = 1.96;

    private EvalMetricsCalculator() {
    }

    /**
     * 单用例判定结果(由评测服务产出,本类只管算指标)
     *
     * @param caseKey        用例标识(展示用)
     * @param group          span / document / legacy
     * @param hit            是否命中
     * @param hitRank        首个完整命中时的名次(1 起;未命中 -1)
     * @param evidenceCount  标注证据数(legacy 固定 1)
     * @param evidenceHits   在 K 条下命中的证据数(legacy = hit ? 1 : 0)
     * @param relevantCount  观测到的相关条数(与任一 span 重叠 > 0;legacy = 满足旧口径的条数)
     * @param ndcg           单用例 NDCG(由 {@link #ndcgOfCase} 按 rel 序列算好传入)
     */
    public record CaseOutcome(String caseKey, String group, boolean hit, int hitRank,
                              int evidenceCount, int evidenceHits, int relevantCount, double ndcg) {
    }

    /**
     * 一组指标(overall 或某分组)
     */
    public record GroupMetrics(String group, int cases,
                               double hitRate, double ciLow, double ciHigh,
                               double recall, double precision, double mrr, double ndcg) {
    }

    /**
     * 完整指标:overall + 各分组(空组已省略)
     */
    public record Metrics(GroupMetrics overall, List<GroupMetrics> groups) {
    }

    /**
     * 计算 K 条下的五指标
     *
     * @param outcomes 每个有效用例的判定结果
     * @param k        召回条数(Precision@K 分母)
     */
    public static Metrics compute(List<CaseOutcome> outcomes, int k) {
        List<GroupMetrics> groups = new ArrayList<>();
        GroupMetrics overall = computeGroup("overall", outcomes, k);
        for (String g : List.of("span", "document", "legacy")) {
            List<CaseOutcome> subset = outcomes.stream().filter(o -> g.equals(o.group())).toList();
            if (!subset.isEmpty()) {
                groups.add(computeGroup(g, subset, k));
            }
        }
        return new Metrics(overall, groups);
    }

    private static GroupMetrics computeGroup(String group, List<CaseOutcome> outcomes, int k) {
        int n = outcomes.size();
        int hits = 0;
        double recallSum = 0;
        double precisionSum = 0;
        double reciprocalSum = 0;
        double ndcgSum = 0;
        for (CaseOutcome o : outcomes) {
            if (o.hit()) {
                hits++;
            }
            recallSum += o.evidenceCount() == 0 ? 0 : o.evidenceHits() / (double) o.evidenceCount();
            precisionSum += o.relevantCount() / (double) k;
            reciprocalSum += o.hit() && o.hitRank() > 0 ? 1.0 / o.hitRank() : 0;
            ndcgSum += o.ndcg();
        }
        double hitRate = n == 0 ? 0 : hits / (double) n;
        double[] ci = wilson(hits, n);
        return new GroupMetrics(group, n, hitRate, ci[0], ci[1],
            n == 0 ? 0 : recallSum / n,
            n == 0 ? 0 : precisionSum / n,
            n == 0 ? 0 : reciprocalSum / n,
            n == 0 ? 0 : ndcgSum / n);
    }

    /**
     * 单用例 NDCG:rel 二值序列 + 标注证据数
     *
     * <p>ideal = min(max(标注数, 观测数), K):用观测值兜底进 ideal,
     * 否则重叠块让观测相关数 > 标注数会把 NDCG 顶过 1。</p>
     *
     * @param relBinary     每个名次是否相关(0/1),长度 <= K
     * @param evidenceCount 标注证据数(legacy 固定 1)
     */
    public static double ndcgOfCase(List<Integer> relBinary, int evidenceCount, int k) {
        double dcg = 0;
        int observed = 0;
        for (int i = 0; i < relBinary.size() && i < k; i++) {
            if (relBinary.get(i) > 0) {
                observed++;
                // 标准 DCG 折扣:rank p = i+1 → 1/log2(p+1)
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int ideal = Math.min(Math.max(evidenceCount, observed), k);
        if (ideal == 0) {
            return 0;
        }
        double idcg = 0;
        for (int i = 1; i <= ideal; i++) {
            idcg += 1.0 / (Math.log(i + 1) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    /**
     * Wilson 95% 置信区间(仅展示,永不参与门禁)
     */
    public static double[] wilson(int hits, int n) {
        if (n == 0) {
            return new double[]{0, 0};
        }
        double p = hits / (double) n;
        double z = WILSON_Z;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denom;
        double spread = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, center - spread), Math.min(1, center + spread)};
    }
}
