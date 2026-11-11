package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 质检结果 agent_cs_quality_check(C2)。 */
@Data
@TableName("agent_cs_quality_check")
public class CsQualityCheck {
    @TableId private Long id;
    private String hitRules, source, reviewStatus;
    private Long messageId, ticketId, workspaceId;
    private Integer score;
    private Date createTime;
}
