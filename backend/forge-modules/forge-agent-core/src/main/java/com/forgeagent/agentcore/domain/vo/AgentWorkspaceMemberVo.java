package com.forgeagent.agentcore.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 工作空间成员视图对象
 *
 * @author ForgeAgent
 */
@Data
public class AgentWorkspaceMemberVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 成员记录主键
     */
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
     * 登录账号
     */
    private String userName;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户状态(0正常 1停用)
     */
    private String userStatus;

    /**
     * 空间角色(admin/developer/runner)
     */
    private String role;

    /**
     * 角色显示名
     */
    private String roleLabel;

    /**
     * 加入时间
     */
    private Date createTime;

}
