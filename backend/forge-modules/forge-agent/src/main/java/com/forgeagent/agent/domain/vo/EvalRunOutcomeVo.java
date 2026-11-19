package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 同步评测执行结果摘要视图对象(全部派生指标)
 *
 * @author ForgeAgent
 */
@Data
public class EvalRunOutcomeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long runId;

    private String status;

    private double avgScore;

    private int totalCases;

    private long p95LatencyMs;

    /** 百分数 0-100 */
    private double refusalRate;

    private boolean judgeEnabled;
}
