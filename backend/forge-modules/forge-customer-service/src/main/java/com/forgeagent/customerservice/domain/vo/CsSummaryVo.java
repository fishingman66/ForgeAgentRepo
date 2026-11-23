package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 转人工摘要视图对象(CsHandoffSummaryService.generate/latest)
 *
 * @author ForgeAgent
 */
@Data
public class CsSummaryVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 一句话摘要 */
    private String oneLineSummary;

    /** 用户意图 */
    private String userIntent;

    private Integer turns;

    /** 用户情绪 */
    private String emotion;

    /** A-2:会话最新情绪分 [-1,1],与呼叫中心 SentimentAnalyzer 同口径;无信号为 0。 */
    private Double sentimentScore;

    /** A-2:负面情绪连续轮数(趋势维度),与转人工 SENTIMENT 规则同源。 */
    private Integer sentimentStreak;

    /** 已尝试方案 */
    private List<String> triedSolutions;

    /** 遗留问题 */
    private List<String> pendingIssues;

    /** 建议下一步 */
    private String suggestedNextStep;

    /** 建议回复(坐席侧展示) */
    private String suggestedReply;

    /** LLM 降级标记 */
    private Boolean degraded;
}
