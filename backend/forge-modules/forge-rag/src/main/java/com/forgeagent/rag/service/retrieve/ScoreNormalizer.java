package com.forgeagent.rag.service.retrieve;

/**
 * 分数归一化(纯函数,便于单测)。
 *
 * <p>余弦取值 [-1,1],直接拿来做阈值/跨库比较时,负相关与"不相关"挤在 0 附近难以区分;
 * 映射到 [0,1] 后,0.5 正好等于正交,阈值语义稳定且与引擎无关(Milvus/应用层余弦一致)。</p>
 *
 * <p><b>只用于展示与跨库比较,不用于改写绝对阈值</b>:存量库配置的阈值是按原始余弦语义写的,
 * 直接改成归一化分会让所有已配阈值的库行为突变。</p>
 *
 * @author ForgeAgent
 */
public final class ScoreNormalizer {

    private ScoreNormalizer() {
    }

    /**
     * 余弦相似度 → [0,1] 单位分
     */
    public static double unitFromCosine(Double cosine) {
        if (cosine == null || cosine.isNaN()) {
            return 0;
        }
        return clamp01((cosine + 1d) / 2d);
    }

    /**
     * 夹到 [0,1],NaN 视为 0
     */
    public static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 1d);
    }

    /**
     * RRF 融合分:仅用于展示,不做归一(排名已消除量纲)
     */
    public static double rrfScore(int rank, int k) {
        int effectiveK = k <= 0 ? 60 : k;
        int effectiveRank = Math.max(rank, 1);
        return 1d / (effectiveK + effectiveRank);
    }
}
