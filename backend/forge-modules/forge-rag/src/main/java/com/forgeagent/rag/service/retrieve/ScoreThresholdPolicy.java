package com.forgeagent.rag.service.retrieve;

import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;

import java.util.ArrayList;
import java.util.List;

/**
 * 相关度阈值策略(纯静态,便于单测)。
 *
 * <p><b>解决什么</b>:阈值该作用在"哪一种分"上,取决于这次检索实际跑到了哪一步——
 * 精排成功就比精排分,只有向量就比余弦,只有全文命中时两种分都没有,必须显式声明
 * "阈值未生效"而不是悄悄放行或悄悄全砍。</p>
 *
 * <p><b>为什么不按配置决定</b>:配置只能表达"我想要阈值",表达不了"这次能比什么"。
 * 精排模型临时不可用时,按配置比精排阈值会把整批结果砍空,而日志里看不出原因。</p>
 *
 * @author ForgeAgent
 */
public final class ScoreThresholdPolicy {

    private ScoreThresholdPolicy() {
    }

    /**
     * 阈值判定结果
     *
     * @param target    本次可比的分数口径(精排分 → 余弦 → null 表示只有全文命中,无从比较)
     * @param threshold 实际生效的阈值;null 表示不过滤
     * @param inactive  配了阈值但无可用分数——调用方应记录该降级,避免"配了阈値却没生效"被忽略
     * @param kept      过滤后保留的命中(保持原顺序)
     */
    public record Decision(ScoreType target, Double threshold, boolean inactive,
                           List<KnowledgeSearchHitVo> kept) {
    }

    /**
     * 按实际执行阶段选择阈值目标并过滤
     *
     * @param hits            候选命中(按当前排序,顺序会被保留)
     * @param cosineThreshold 余弦阈值(库级 score_threshold,原始余弦口径 -1~1)
     * @param rerankThreshold 精排阈值(库级 rerank_threshold,0~1;为空时退化用 cosineThreshold)
     */
    public static Decision apply(List<KnowledgeSearchHitVo> hits,
                                 Double cosineThreshold, Double rerankThreshold) {
        if (hits == null || hits.isEmpty()) {
            return new Decision(null, null, false, List.of());
        }
        ScoreType target = decideTarget(hits);
        // 枚举 switch 对 null 会抛 NPE,只有全文命中时 target 就是 null
        Double threshold = target == null ? null : switch (target) {
            case RERANK -> rerankThreshold != null ? rerankThreshold : cosineThreshold;
            case VECTOR_COSINE -> cosineThreshold;
            default -> null;
        };
        boolean requested = cosineThreshold != null || rerankThreshold != null;
        if (threshold == null) {
            // 阈值未生效:给命中打上口径,便于前端/日志区分"没过滤"与"过滤后为空"
            annotate(hits);
            return new Decision(target, null, requested, hits);
        }
        List<KnowledgeSearchHitVo> kept = new ArrayList<>(hits.size());
        for (KnowledgeSearchHitVo hit : hits) {
            if (pass(hit, cosineThreshold, rerankThreshold)) {
                kept.add(hit);
            }
        }
        annotate(kept);
        return new Decision(target, threshold, false, kept);
    }

    /**
     * 选择阈值目标:精排分优先,其次余弦;只有全文命中时无可用口径
     */
    static ScoreType decideTarget(List<KnowledgeSearchHitVo> hits) {
        boolean hasRerank = false;
        boolean hasVector = false;
        for (KnowledgeSearchHitVo hit : hits) {
            if (hit.getRerankScore() != null) {
                hasRerank = true;
            }
            if (hit.getVectorScore() != null) {
                hasVector = true;
            }
        }
        if (hasRerank) {
            return ScoreType.RERANK;
        }
        return hasVector ? ScoreType.VECTOR_COSINE : null;
    }

    /**
     * 单条命中是否过阈值
     *
     * <p>仅全文命中的分块保留:BM25 原始分无上界、与余弦不可比,用余弦阈值去砍它既不公平
     * 也砍不准;术语精确匹配本身是可信信号,但必须在 {@code scoreType} 上标出来。</p>
     */
    static boolean pass(KnowledgeSearchHitVo hit, Double cosineThreshold, Double rerankThreshold) {
        if (hit.getRerankScore() != null) {
            Double t = rerankThreshold != null ? rerankThreshold : cosineThreshold;
            return t == null || hit.getRerankScore() >= t;
        }
        if (hit.getVectorScore() != null) {
            return cosineThreshold == null || hit.getVectorScore() >= cosineThreshold;
        }
        return true;
    }

    /**
     * 给命中回填分数口径(展示与排障用,不影响数值)
     */
    private static void annotate(List<KnowledgeSearchHitVo> hits) {
        if (hits == null) {
            return;
        }
        for (KnowledgeSearchHitVo hit : hits) {
            hit.setScoreType(scoreTypeOf(hit).code());
        }
    }

    private static ScoreType scoreTypeOf(KnowledgeSearchHitVo hit) {
        if (hit.getRerankScore() != null) {
            return ScoreType.RERANK;
        }
        if (hit.getVectorScore() != null) {
            // 以单条命中的实际分数为准:配置了精排阈值但本批未跑精排时,口径仍是余弦
            return ScoreType.VECTOR_COSINE;
        }
        return ScoreType.BM25;
    }
}
