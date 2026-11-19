package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 知识库评测运行结果视图对象:run 汇总 + 五指标明细 + 逐用例判定
 *
 * @author ForgeAgent
 */
@Data
public class KbEvalRunResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本次运行落库记录
     */
    private AgentKbEvalRunVo run;

    /**
     * HitRate@K(与 run.hitRate 同值,保留扁平字段供旧前端直接读取)
     */
    private Double hitRate;

    /**
     * 五指标 + 分组 + 置信区间
     */
    private KbEvalMetricsVo metrics;

    /**
     * 已废弃用例数(不入指标)
     */
    private Integer deprecatedCases;

    /**
     * 逐用例判定明细
     */
    private List<KbEvalCaseResultVo> caseResults;

}
