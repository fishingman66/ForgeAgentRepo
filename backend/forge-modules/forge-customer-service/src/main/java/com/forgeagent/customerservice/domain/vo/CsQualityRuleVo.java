package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsQualityRule;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 质检规则视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsQualityRule.class)
public class CsQualityRuleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String ruleType;
    private String pattern;
    private String severity;
    private String enabled;
    private Long workspaceId;
    private Integer scoreDelta;
    private Date createTime;
}
