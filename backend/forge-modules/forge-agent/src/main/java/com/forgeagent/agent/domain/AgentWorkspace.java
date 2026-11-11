package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * Agent 工作空间对象 agent_workspace
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_workspace")
public class AgentWorkspace extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 空间名称(全局唯一,2-30字符)
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
     * 空间所有者(首任空间管理员)用户ID
     */
    private Long ownerId;

    /**
     * 归属部门ID(管理归属,可转移;与 create_dept 审计快照分离)
     */
    private Long deptId;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
