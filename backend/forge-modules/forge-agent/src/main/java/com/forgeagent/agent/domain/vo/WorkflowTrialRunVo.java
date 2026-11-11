package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 工作流试运行结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class WorkflowTrialRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 执行状态(success/fail)
     */
    private String status;

    /**
     * 结果说明
     */
    private String message;

    /**
     * 试运行输入
     */
    private String inputs;

    /**
     * 节点执行轨迹
     */
    private List<NodeTrace> trace;

    /**
     * 工作流运行实例ID(触发人工审批暂停时返回,其余为空)
     */
    private Long runId;

    /**
     * 关联审批单ID(触发人工审批暂停时返回,其余为空)
     */
    private Long approvalId;

    @Data
    public static class NodeTrace implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String nodeId;
        private String nodeName;
        private String nodeType;
        private String status;
        /**
         * 节点执行输出(LLM/结束节点真实执行时填充)
         */
        private String output;
    }
}
