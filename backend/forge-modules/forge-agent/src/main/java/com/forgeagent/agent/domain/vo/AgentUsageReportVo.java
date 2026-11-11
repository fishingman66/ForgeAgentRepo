package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Token 用量统计报表视图对象
 *
 * @author ForgeAgent
 */
@Data
public class AgentUsageReportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 总 Token
     */
    private Long totalTokens;

    /**
     * 输入 Token 合计
     */
    private Long promptTokens;

    /**
     * 输出 Token 合计
     */
    private Long completionTokens;

    /**
     * 调用次数
     */
    private Long callCount;

    /**
     * 估算占比(0-100)
     */
    private Double estimatedRatio;

    /**
     * 数据截止时间(当天数据近实时)
     */
    private String dataEndTime;

    /**
     * 按日趋势
     */
    private List<DailyItem> dailyTrend;

    /**
     * 按 Agent 排行
     */
    private List<RankItem> agentRank;

    /**
     * 按模型排行
     */
    private List<RankItem> modelRank;

    /**
     * 按日趋势项
     */
    @Data
    public static class DailyItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String statDate;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private Long callCount;
    }

    /**
     * 排行项
     */
    @Data
    public static class RankItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String name;
        private Long totalTokens;
        private Long callCount;
        private Double ratio;
    }

}
