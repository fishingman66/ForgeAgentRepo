package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 成本总览视图对象(CsCostService.overview;结构对齐前端 CsCostOverview)
 *
 * @author ForgeAgent
 */
@Data
public class CsCostOverviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日明细 */
    private List<com.forgeagent.customerservice.domain.CsCostDaily> days;

    /** 汇总指标 */
    private Total total;

    /** 单价说明(未配置时提示) */
    private String priceNote;

    /**
     * 汇总指标嵌套对象
     */
    @Data
    public static class Total implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long totalTokens;

        private Integer callCount;

        private Integer conversationCount;

        private Integer resolvedCount;

        /** 每 conversation 平均 token(派生) */
        private Double tokensPerConversation;

        /** 每 resolved 平均 token(派生) */
        private Double tokensPerResolved;

        /** 成本金额(未配置单价时为 null) */
        private java.math.BigDecimal costAmount;

        /** 是否已配置单价 */
        private Boolean amountAvailable;

        private String currency;
    }
}
