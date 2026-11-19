package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作流对外调用结果视图对象(G2,免登录入口)
 *
 * @author ForgeAgent
 */
@Data
public class AgentWorkflowApiInvokeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 运行记录ID
     */
    private Long runId;

    /**
     * 终态状态:pending_approval / success / failed 等
     */
    private String status;

    /**
     * 端节点输出(模板渲染后的终态结果)
     */
    private String output;

    /**
     * 运行消息(失败原因或审批提示)
     */
    private String message;

    /**
     * 调用耗时(毫秒,含排队与执行)
     */
    private Long costMs;

}
