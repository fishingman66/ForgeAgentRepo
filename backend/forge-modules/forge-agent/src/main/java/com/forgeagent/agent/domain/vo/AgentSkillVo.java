package com.forgeagent.agent.domain.vo;

import com.forgeagent.agent.domain.AgentSkill;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 技能包视图对象(08方案 / A1)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSkill.class)
public class AgentSkillVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 技能名称
     */
    private String name;

    /**
     * 一行摘要
     */
    private String description;

    /**
     * 操作手册(列表页不返回,详情页返回)
     */
    private String instructions;

    /**
     * 引用平台工具ID列表(逗号分隔)
     */
    private String toolIds;

    /**
     * 引用MCP服务器ID列表(逗号分隔)
     */
    private String mcpIds;

    /**
     * 引用知识库ID列表(逗号分隔)
     */
    private String knowledgeIds;

    /**
     * 加载后追加的提示词片段
     */
    private String promptFragment;

    /**
     * 技能版本
     */
    private Integer version;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 归属工作空间
     */
    private Long workspaceId;

    /**
     * 备注
     */
    private String remark;

    private Date createTime;

    private Date updateTime;
}
