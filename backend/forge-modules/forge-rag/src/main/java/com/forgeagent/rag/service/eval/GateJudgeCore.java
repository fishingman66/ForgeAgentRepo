package com.forgeagent.rag.service.eval;

import java.util.List;

/**
 * R3-4/R3-6 门禁判决核心(纯静态,零业务依赖)。
 *
 * <p>Agent 发布门禁与 KB 重建门禁各自组装入参,共用本组件 —— 改一处两边生效。
 * 判决准备顺序即规范,任何一步命中即短路返回:</p>
 * <ol>
 *   <li>无可比用例 → LOG_ONLY / INSUFFICIENT_CASES</li>
 *   <li>两侧 corpus_fingerprint 或 dataset_revision 不等 → LOG_ONLY / NOT_COMPARABLE</li>
 *   <li>用例过期比例 > 0.15 → LOG_ONLY / STALE_RATIO_EXCEEDED</li>
 *   <li>任一侧存在 degraded 用例 → LOG_ONLY / DEGRADED_CASES</li>
 *   <li>否则比 HitRate / Recall(两侧都已按交集重算)</li>
 * </ol>
 *
 * <p>ε = max(0.02, 1/N),N = 交集用例数;delta < -ε → BLOCKED / METRICS_REGRESSED。
 * 首次(无基线)→ PASSED / BASELINE_RECORDED,永不因"没有基线"拦截。
 * Wilson 置信区间只做展示,不参与门禁 —— 噪声控制只用 ε,禁止两套机制并存。</p>
 *
 * @author ForgeAgent
 */
public final class GateJudgeCore {

    /**
     * 用例过期比例上限
     */
    public static final double STALE_RATIO_LIMIT = 0.15;

    public enum State {PASSED, BLOCKED, LOG_ONLY}

    public enum Reason {
        METRICS_REGRESSED, WITHIN_TOLERANCE, BASELINE_RECORDED,
        NOT_COMPARABLE, STALE_RATIO_EXCEEDED, DEGRADED_CASES, INSUFFICIENT_CASES
    }

    /**
     * 门禁一侧的指标输入(两侧都必须是同一口径:交集用例重算后的值)
     *
     * @param hitRate           HitRate@K
     * @param recall            Recall@K
     * @param effectiveCases    有效(交集)用例数
     * @param staleCases        证据过期用例数
     * @param degradedCases     检索降级用例数
     * @param corpusFingerprint 语料指纹
     * @param datasetRevision   数据集修订号
     */
    public record Side(double hitRate, double recall, int effectiveCases,
                       int staleCases, int degradedCases,
                       String corpusFingerprint, String datasetRevision) {
    }

    /**
     * 判决结果
     *
     * @param epsilon 实际使用的容差(展示与 BLOCKED 强制时记录)
     */
    public record Verdict(State state, Reason reason, double epsilon, double delta,
                          double baselineHitRate, double candidateHitRate) {
    }

    private GateJudgeCore() {
    }

    /**
     * @param baseline 基线侧;null = 首次运行(无基线)
     * @param candidate 候选侧
     */
    public static Verdict judge(Side baseline, Side candidate) {
        if (candidate == null || candidate.effectiveCases() <= 0) {
            return new Verdict(State.LOG_ONLY, Reason.INSUFFICIENT_CASES, 0, 0, 0, 0);
        }
        double epsilon = Math.max(0.02, 1.0 / candidate.effectiveCases());
        if (baseline == null) {
            return new Verdict(State.PASSED, Reason.BASELINE_RECORDED, epsilon, 0, 0,
                candidate.hitRate());
        }
        if (!sameText(baseline.corpusFingerprint(), candidate.corpusFingerprint())
            || !sameText(baseline.datasetRevision(), candidate.datasetRevision())) {
            return new Verdict(State.LOG_ONLY, Reason.NOT_COMPARABLE, epsilon, 0,
                baseline.hitRate(), candidate.hitRate());
        }
        int total = candidate.effectiveCases() + candidate.staleCases();
        if (total > 0 && candidate.staleCases() / (double) total > STALE_RATIO_LIMIT) {
            return new Verdict(State.LOG_ONLY, Reason.STALE_RATIO_EXCEEDED, epsilon, 0,
                baseline.hitRate(), candidate.hitRate());
        }
        if (candidate.degradedCases() > 0 || baseline.degradedCases() > 0) {
            return new Verdict(State.LOG_ONLY, Reason.DEGRADED_CASES, epsilon, 0,
                baseline.hitRate(), candidate.hitRate());
        }
        double deltaHit = candidate.hitRate() - baseline.hitRate();
        double deltaRecall = candidate.recall() - baseline.recall();
        double delta = Math.min(deltaHit, deltaRecall);
        if (delta < -epsilon - 1e-9) {
            return new Verdict(State.BLOCKED, Reason.METRICS_REGRESSED, epsilon, delta,
                baseline.hitRate(), candidate.hitRate());
        }
        return new Verdict(State.PASSED, Reason.WITHIN_TOLERANCE, epsilon, delta,
            baseline.hitRate(), candidate.hitRate());
    }

    /**
     * ε 边界外的快速判定(供单测与人工验算)
     */
    public static double tolerance(int effectiveCases) {
        return effectiveCases <= 0 ? 0 : Math.max(0.02, 1.0 / effectiveCases);
    }

    /**
     * 三态转可继续/需强制/仅记录的可读说明
     */
    public static String describe(Verdict v) {
        return switch (v.state()) {
            case PASSED -> "门禁通过:" + v.reason() + " (delta=" + fmt(v.delta())
                + ", ε=" + fmt(v.epsilon()) + ")";
            case BLOCKED -> "门禁拦截:" + v.reason() + " (delta=" + fmt(v.delta())
                + " < -ε=" + fmt(v.epsilon()) + "),需显式强制";
            case LOG_ONLY -> "仅记录不拦截:" + v.reason();
        };
    }

    private static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    /**
     * 判决准备顺序的合法性自检(单测用,防止顺序被改动)
     */
    public static List<Reason> decisionOrder() {
        return List.of(Reason.INSUFFICIENT_CASES, Reason.NOT_COMPARABLE,
            Reason.STALE_RATIO_EXCEEDED, Reason.DEGRADED_CASES, Reason.METRICS_REGRESSED);
    }
}
