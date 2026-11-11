package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * Agent 触发器执行记录对象 agent_trigger_log
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_trigger_log")
public class AgentTriggerLog extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 触发器ID
     */
    private Long triggerId;

    /**
     * 触发器名称(冗余)
     */
    private String triggerName;

    /**
     * 触发类型(cron/webhook)
     */
    private String triggerType;

    /**
     * 目标类型(agent/workflow)
     */
    private String targetType;

    /**
     * 目标ID
     */
    private Long targetId;

    /**
     * 调用来源(cron定时/webhook事件/manual手动)
     */
    private String invokeSource;

    /**
     * 执行状态(success/fail)
     */
    private String status;

    /**
     * 执行结果说明
     */
    private String message;

    /**
     * 执行耗时(毫秒)
     */
    private Long costMs;

    /**
     * 执行时间
     */
    private Date runTime;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
