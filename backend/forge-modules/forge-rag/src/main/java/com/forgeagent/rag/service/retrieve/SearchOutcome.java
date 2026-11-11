package com.forgeagent.rag.service.retrieve;

import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;

import java.util.List;

/**
 * 一次检索的完整回执:命中 + 本次走成什么样。
 *
 * <p>以前接口只返回 {@code List<hit>},调用方无从知道"为什么只召回 2 条":
 * 是向量路挂了?精排超时了?还是阈值把结果砍了?这些都只在服务端日志里,
 * 而日志没有 query 上下文,事后无法归因。回执把"结果"和"过程"一起交出去。</p>
 *
 * @param hits               最终命中(已排序、已过滤)
 * @param degraded           本次发生的降级原因(空表示全链路正常)
 * @param thresholdAppliedOn 阈值实际作用于哪种分数;null 表示本次未过滤
 * @param vectorHits         向量路召回条数(过滤前)
 * @param keywordHits        关键词路召回条数(过滤前)
 * @param elapsedMs          检索耗时(毫秒)
 * @author ForgeAgent
 */
public record SearchOutcome(List<KnowledgeSearchHitVo> hits,
                            List<DegradedReason> degraded,
                            ScoreType thresholdAppliedOn,
                            int vectorHits,
                            int keywordHits,
                            long elapsedMs) {

    /**
     * 空结果(参数不合法 / 知识库停用前的兜底)
     */
    public static SearchOutcome empty() {
        return new SearchOutcome(List.of(), List.of(), null, 0, 0, 0);
    }

    /**
     * 是否有降级发生
     */
    public boolean isDegraded() {
        return degraded != null && !degraded.isEmpty();
    }

    /**
     * 降级原因转成逗号分隔的 code,便于落库与日志
     */
    public String degradedCodeText() {
        return degraded == null || degraded.isEmpty() ? ""
            : degraded.stream().map(DegradedReason::code)
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 首条分数;无命中返回 null
     */
    public Double topScore() {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        KnowledgeSearchHitVo first = hits.get(0);
        if (first.getRerankScore() != null) {
            return first.getRerankScore();
        }
        if (first.getVectorScore() != null) {
            return first.getVectorScore();
        }
        return first.getScore();
    }
}
