package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 坐席辅助总览视图对象(CsAssistMetricService.overview;结构对齐前端 CsAssistOverview)
 *
 * @author ForgeAgent
 */
@Data
public class CsAssistOverviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日明细 */
    private List<com.forgeagent.customerservice.domain.CsAssistMetricDaily> days;

    /** 汇总指标 */
    private Total total;

    /** 分母口径说明 */
    private String rateBasis;

    /**
     * 汇总指标嵌套对象
     */
    @Data
    public static class Total implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long suggestionCount;

        private Long adoptedCount;

        /** 采纳率(0-1) */
        private Double adoptRate;

        private Long actionCount;

        private Long intentCount;

        /** 意图识别率(0-1) */
        private Double intentRate;

        private Long recycledCount;
    }
}
