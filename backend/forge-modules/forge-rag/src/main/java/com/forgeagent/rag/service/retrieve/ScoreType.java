package com.forgeagent.rag.service.retrieve;

/**
 * 检索命中所携带分数的口径。
 *
 * <p>以前只有裸的 {@code score} 一个数字:既可能是 RRF 融合分(<b>只在本次 query 内可比</b>,
 * 量级 0.016~0.033),也可能是余弦(-1~1),还可能是精排分(0~1)。三者混用直接导致
 * "拿 0.05 去过滤 RRF 分"这类量纲事故——生产默认阈值 0.05 高于 RRF 上限,对话链路恒零命中。
 * 分数必须自带口径,阈值才可能配得对。</p>
 *
 * @author ForgeAgent
 */
public enum ScoreType {

    /**
     * 向量余弦相似度(原始值,-1~1),跨 query 可比
     */
    VECTOR_COSINE("cosine", true),

    /**
     * RRF 融合分 Σ1/(k+rank),仅本次 query 内相对可比,不可当绝对阈值
     */
    RRF("rrf", false),

    /**
     * 重排序模型分(0~1),跨 query 可比
     */
    RERANK("rerank", true),

    /**
     * 仅命中全文索引:原始 BM25 分无上界,只能用于排序
     */
    BM25("bm25", false);

    private final String code;

    /**
     * 是否可作绝对阈值(不可比的分一旦当阈值用,会随 query 长度/分块数漂移)
     */
    private final boolean thresholdable;

    ScoreType(String code, boolean thresholdable) {
        this.code = code;
        this.thresholdable = thresholdable;
    }

    public String code() {
        return code;
    }

    public boolean thresholdable() {
        return thresholdable;
    }
}
