package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * AI 工单根因调查(B4) agent_cs_investigation。
 *
 * <p>一次调查 = 一组假设 + 若干只读取数证据 + 一份报告;报告需坐席确认后才算回填工单。</p>
 */
@Data
@TableName("agent_cs_investigation")
public class CsInvestigation {
    @TableId
    private Long id;
    private Long workspaceId;
    private Long ticketId;
    private Long conversationId;
    private Long datasourceId;
    private Long agentId;
    private Long workflowRunId;
    private String templateCode;
    /** RUNNING/SUCCEEDED/FAILED/CONFIRMED/REJECTED */
    private String status;
    private String hypothesesJson;
    private String reportMd;
    private BigDecimal confidence;
    private String errorMessage;
    private Long createdBy;
    private Long confirmedBy;
    private Date confirmedAt;
    private Date createTime;
    private Date updateTime;
}
