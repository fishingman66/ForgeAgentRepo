package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentTrigger;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Agent 触发器视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentTrigger.class)
public class AgentTriggerVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private Long workspaceId;

    /** 触发类型(cron/webhook) */
    private String triggerType;

    /** 目标类型(agent/workflow) */
    private String targetType;

    /** 目标ID */
    private Long targetId;

    /** 目标名称(Agent 名称,展示用) */
    private String targetName;

    /** 输入模板 */
    private String inputs;

    /** Cron 表达式 */
    private String cronExpr;

    /** Webhook 密钥 */
    private String secret;

    /** 是否启用(0启用 1停用) */
    private String enabled;

    /** 最近执行时间 */
    private Date lastRunTime;

    /** 最近执行状态(success/fail) */
    private String lastRunStatus;

    /** 累计执行次数 */
    private Integer runCount;

    private String remark;

    private Date createTime;

    private Date updateTime;

}
