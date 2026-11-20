package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 调查证据链(B4) agent_cs_investigation_evidence:每条取数一行,只追加。
 */
@Data
@TableName("agent_cs_investigation_evidence")
public class CsInvestigationEvidence {
    @TableId
    private Long id;
    private Long investigationId;
    private Integer seq;
    /** SQL/MCP/KB/TICKET_EVENT */
    private String evidenceType;
    private String hypothesisTitle;
    private String queryText;
    private String resultDigest;
    private Integer rowCount;
    private Integer elapsedMs;
    private Integer success;
    private String errorMessage;
    private Date createTime;
}
