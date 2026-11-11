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
 * Agent 触发器对象 agent_trigger
 *
 * <p>支持 Cron 定时触发与 Webhook 事件触发两类,目标是 Agent(对话)或 Agent 工作流。</p>
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_trigger")
public class AgentTrigger extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 触发器名称
     */
    private String name;

    /**
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * 触发类型(cron定时/webhook事件)
     */
    private String triggerType;

    /**
     * 目标类型(agent对话/workflow工作流)
     */
    private String targetType;

    /**
     * 目标ID(AgentID;工作流即该 Agent 的工作流)
     */
    private Long targetId;

    /**
     * 输入模板(agent:消息文本;workflow:输入变量JSON)
     */
    private String inputs;

    /**
     * Cron 表达式(定时触发器)
     */
    private String cronExpr;

    /**
     * Webhook 密钥(事件触发器)
     */
    private String secret;

    /**
     * 是否启用(0启用 1停用)
     */
    private String enabled;

    /**
     * 最近执行时间
     */
    private Date lastRunTime;

    /**
     * 最近执行状态(success/fail)
     */
    private String lastRunStatus;

    /**
     * 累计执行次数
     */
    private Integer runCount;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
