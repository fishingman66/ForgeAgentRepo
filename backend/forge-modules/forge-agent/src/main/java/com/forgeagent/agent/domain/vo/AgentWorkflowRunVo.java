package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentWorkflowRun;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 工作流运行实例视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentWorkflowRun.class)
public class AgentWorkflowRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * 状态(pending_approval/success/fail)
     */
    private String status;

    /**
     * 试运行输入(列表摘要展示)
     */
    private String inputs;

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
    private Date createTime;

}
