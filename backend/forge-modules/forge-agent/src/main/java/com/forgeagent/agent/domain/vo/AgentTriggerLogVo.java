package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentTriggerLog;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Agent 触发器执行记录视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentTriggerLog.class)
public class AgentTriggerLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long triggerId;

    private String triggerName;

    private String triggerType;

    private String targetType;

    private Long targetId;

    /** 调用来源(cron/webhook/manual) */
    private String invokeSource;

    /** 执行状态(success/fail) */
    private String status;

    private String message;

    private Long costMs;

    private Date runTime;

    private Date createTime;

}
