package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 工作流版本快照对象 agent_workflow_version
 *
 * <p>每次保存工作流自动生成递增版本快照,支持历史版本查看与回滚。</p>
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_workflow_version")
public class AgentWorkflowVersion extends BaseEntity {

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
     * 版本号(按Agent递增)
     */
    private Integer version;

    /**
     * 工作流JSON快照
     */
    private String workflowJson;

    /**
     * 版本备注
     */
    private String remark;

    /**
     * 删除标志(0存在 其他删除)
     */
    @TableLogic
    private Long delFlag;

}
