package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库评测双跑对比结果视图对象(R3-4):交集重算后的判决 + 两侧指标
 *
 * @author ForgeAgent
 */
@Data
public class KbEvalCompareVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 判决状态:PASSED / BLOCKED / LOG_ONLY
     */
    private String state;

    /**
     * 判决原因
     */
    private String reason;

    /**
     * 实际使用的容差 ε
     */
    private Double epsilon;

    /**
     * 候选侧相对基线的指标变化量
     */
    private Double delta;

    /**
     * 基线侧 HitRate(交集用例重算)
     */
    private Double baselineHitRate;

    /**
     * 候选侧 HitRate(交集用例重算)
     */
    private Double candidateHitRate;

    /**
     * 两侧用例交集规模
     */
    private Integer intersectionCases;

    /**
     * 判决的可读描述
     */
    private String description;

}
