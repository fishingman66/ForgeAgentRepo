package com.forgeagent.agent.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Token 用量报表查询条件
 *
 * @author ForgeAgent
 */
@Data
public class AgentTokenUsageBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作空间ID(平台管理员可跨空间,空间成员按空间过滤)
     */
    private Long workspaceId;

    /**
     * 归属部门ID(平台管理视角按部门过滤,映射到空间集合)
     */
    private Long deptId;

    /**
     * Agent ID
     */
    private Long agentId;

    /**
     * 模型标识(模糊)
     */
    private String modelName;

    /**
     * 成员用户ID
     */
    private Long userId;

    /**
     * 来源(chat/api/debug)
     */
    private String source;

    /**
     * 是否估算(0实测 1估算)
     */
    private String estimated;

    /**
     * 会话ID(明细下钻)
     */
    private Long sessionId;

    /**
     * 开始日期(yyyy-MM-dd HH:mm:ss)
     */
    private String beginTime;

    /**
     * 结束日期(yyyy-MM-dd HH:mm:ss)
     */
    private String endTime;

}
