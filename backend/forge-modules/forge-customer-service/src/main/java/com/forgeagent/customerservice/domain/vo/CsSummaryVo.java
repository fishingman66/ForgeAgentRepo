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
