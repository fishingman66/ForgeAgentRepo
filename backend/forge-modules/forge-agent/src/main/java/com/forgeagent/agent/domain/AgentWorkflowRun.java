package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 工作流运行实例对象 agent_workflow_run
 *
 * <p>人工审批节点触发暂停时持久化运行状态(工作流快照/变量上下文/执行轨迹/
 * 已完成与已跳过节点),审批通过后据此恢复续跑。</p>
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_workflow_run")
public class AgentWorkflowRun extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
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
     * 工作流JSON快照
     */
    private String workflowJson;

    /**
     * 试运行输入
     */
    private String inputs;

    /**
     * G3:幂等键(agent 内唯一,防触发器重复投递;手动触发为空)
     */
    private String idempotencyKey;

    /**
     * 变量上下文快照(JSON 对象)
     */
    private String varsJson;

    /**
     * 节点执行轨迹快照(JSON 数组)
     */
    private String traceJson;

    /**
     * 已完成节点ID列表(JSON 数组)
     */
    private String completedJson;

    /**
     * 已跳过节点ID列表(JSON 数组)
     */
    private String skippedJson;

    /**
     * 状态(pending_approval/success/fail)
     */
    private String status;

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
     * 删除标志(0存在 其他删除)
     */
    @TableLogic
    private Long delFlag;

}
