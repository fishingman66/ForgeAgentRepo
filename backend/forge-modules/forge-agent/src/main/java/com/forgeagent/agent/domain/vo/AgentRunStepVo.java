package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentRunStep;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 运行步骤明细视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = com.forgeagent.agent.domain.AgentRunStep.class)
public class AgentRunStepVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer stepNo;

    private String stepType;

    private String stepName;

    /** success/failed/skipped */
    private String status;

    private Long latencyMs;

    private Integer tokenUsage;

    /** 阶段载荷 JSON(截断后) */
    private String payload;

    private Date createTime;
}
