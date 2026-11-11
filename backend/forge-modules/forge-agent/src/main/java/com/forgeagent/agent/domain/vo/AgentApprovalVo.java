package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentApproval;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 工具审批单视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentApproval.class)
public class AgentApprovalVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
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
     * MCP服务器名称
     */
    private String serverName;

    /**
     * 调用参数(JSON)
     */
    private String callArgs;

    /**
     * 状态
     */
    private String status;

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
     * 创建时间
     */
    private Date createTime;

}
