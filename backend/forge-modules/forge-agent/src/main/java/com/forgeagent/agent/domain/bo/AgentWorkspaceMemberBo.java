package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

/**
 * 工作空间成员业务对象
 *
 * @author ForgeAgent
 */
@Data
public class AgentWorkspaceMemberBo {

    /**
     * 成员记录主键(变更角色/移除时使用)
     */
    @NotNull(message = "成员记录ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 工作空间ID
     */
    @NotNull(message = "工作空间ID不能为空", groups = {AddGroup.class})
    private Long workspaceId;

    /**
     * 系统用户ID
     */
    @NotNull(message = "用户ID不能为空", groups = {AddGroup.class})
    private Long userId;

    /**
     * 空间角色(admin/developer/runner)
     */
    @NotBlank(message = "空间角色不能为空", groups = {AddGroup.class, EditGroup.class})
    private String role;

}
