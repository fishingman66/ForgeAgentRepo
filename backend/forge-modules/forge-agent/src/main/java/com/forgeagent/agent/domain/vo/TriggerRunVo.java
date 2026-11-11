package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 触发器执行结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class TriggerRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 触发器ID */
    private Long triggerId;

    /** 执行状态(success/fail) */
    private String status;

    /** 结果说明 */
    private String message;

    /** 执行耗时(毫秒) */
    private Long costMs;

    /** 目标类型(agent/workflow) */
    private String targetType;

    /** 目标ID */
    private Long targetId;

    /** 目标名称 */
    private String targetName;

    /** 生成或复用的会话ID(agent 目标时有值) */
    private Long sessionId;

    /** 工作流运行实例ID(workflow 目标时有值) */
    private Long runId;

    /** 助手回复内容(agent 目标时截取展示) */
    private String output;

    /** 工作流最终状态(workflow 目标时:success/fail/pending_approval) */
    private String runStatus;

}
