package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作流运行回放详情视图对象
 *
 * <p>携带执行时刻的工作流图快照、输入、变量上下文与节点轨迹,
 * 前端据此在画布上按节点状态着色回放执行过程。</p>
 *
 * @author ForgeAgent
 */
@Data
public class WorkflowRunDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 运行实例ID
     */
    private Long id;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * 状态(pending_approval/success/fail)
     */
    private String status;

    /**
     * 试运行输入
     */
    private String inputs;

    /**
     * 执行时刻的工作流JSON快照
     */
    private String workflowJson;

    /**
     * 变量上下文快照(JSON 对象字符串)
     */
    private String varsJson;

    /**
     * 等待审批的节点ID
     */
    private String pendingNode;

    /**
     * 关联审批单ID
     */
    private Long approvalId;

    /**
     * 发起人用户ID
     */
    private Long userId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 节点执行轨迹(结构化)
     */
    private List<WorkflowTrialRunVo.NodeTrace> trace;

}
