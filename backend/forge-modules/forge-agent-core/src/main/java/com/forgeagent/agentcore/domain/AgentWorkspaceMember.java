package com.forgeagent.agentcore.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 工作空间成员对象 agent_workspace_member
 * <p>成员移除为物理删除,不留软删记录,便于同一用户重复加入</p>
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_workspace_member")
public class AgentWorkspaceMember implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * 系统用户ID
     */
    private Long userId;

    /**
     * 空间角色(admin/developer/runner)
     */
    private String role;

    /**
     * 创建人
     */
    private Long createBy;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新人
     */
    private Long updateBy;

    /**
     * 更新时间
     */
    private Date updateTime;

}
