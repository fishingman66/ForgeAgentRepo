package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentWorkflowVersion;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作流版本快照视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentWorkflowVersion.class)
public class AgentWorkflowVersionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 版本备注
     */
    private String remark;

    /**
     * 工作流JSON快照(仅详情接口返回)
     */
    private String workflowJson;

    /**
     * 创建者
     */
    private Long createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
