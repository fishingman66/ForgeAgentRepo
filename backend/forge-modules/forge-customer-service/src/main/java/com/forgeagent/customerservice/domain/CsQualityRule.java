package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 质检规则 agent_cs_quality_rule(C2),workspace_id 为空表示平台级规则。 */
@Data
@TableName("agent_cs_quality_rule")
public class CsQualityRule {
    @TableId private Long id;
    private String ruleType, pattern, severity, enabled;
    private Long workspaceId;
    private Integer scoreDelta;
    private Date createTime;
}
