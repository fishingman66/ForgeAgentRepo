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
 * Agent 工具审批单对象 agent_approval
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_approval")
public class AgentApproval extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * 请求轮次ID
     */
    private String roundId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具来源(mcp/platform-http)
     */
    private String toolSource;

    /**
     * MCP服务器名称(平台工具为空)
     */
    private String serverName;

    /**
     * 调用参数(JSON)
     */
    private String callArgs;

    /**
     * 状态(pending/approved/rejected/cancelled/expired)
     */
    private String status;

    /**
     * G1:审批截止时间(过期清扫任务置 expired,关联工作流 run 置 fail)
     */
    private java.util.Date expiredAt;

    /**
     * 审批说明/拒绝原因
     */
    private String resultNote;

    /**
     * 审批人
     */
    private Long approveBy;

    /**
     * 审批时间
     */
    private Date approveTime;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
