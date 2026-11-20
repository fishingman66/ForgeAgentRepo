package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** F2 工作空间邀请令牌(库里只存 SHA-256,明文 token 生成时回传一次)。 */
@Data
@TableName("agent_workspace_invite")
public class AgentWorkspaceInvite {
    @TableId
    private Long id;
    private Long workspaceId;
    private String tokenHash;
    private String role;
    private Integer maxUses;
    private Integer usedCount;
    private Date expiresAt;
    private Long createBy;
    private Date createTime;
}
