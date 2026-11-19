package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库评测分组指标视图对象(overall 或某个判定分组)
 *
 * @author ForgeAgent
 */
@Data
public class KbEvalGroupMetricsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分组名:overall / span / document / legacy
     */
    private String group;

    /**
     * 该分组用例数
     */
    private Integer cases;

    /**
     * HitRate@K
     */
    private Double hitRate;

    /**
     * HitRate 的 Wilson 95% 置信区间下界
     */
    private Double ciLow;

    /**
     * HitRate 的 Wilson 95% 置信区间上界
     */
    private Double ciHigh;

    /**
     * Recall@K
     */
    private Double recall;

    /**
     * Precision@K
     */
    private Double precision;

    /**
     * MRR
     */
    private Double mrr;

    /**
     * NDCG@K
     */
    private Double ndcg;

}
