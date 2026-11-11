package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentWorkspace;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 工作空间视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentWorkspace.class)
public class AgentWorkspaceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 空间名称
     */
    private String name;

    /**
     * 空间描述
     */
    private String description;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 空间所有者用户ID
     */
    private Long ownerId;

    /**
     * 空间所有者昵称
     */
    private String ownerName;

    /**
     * 归属部门ID
     */
    private Long deptId;

    /**
     * 归属部门名称
     */
    private String deptName;

    /**
     * 成员数量
     */
    private Integer memberCount;

    /**
     * 当前用户在该空间内的角色(未加入为空)
     */
    private String myRole;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
