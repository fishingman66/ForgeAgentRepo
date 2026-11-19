package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentPromptTemplate;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Prompt 模板视图对象(逻辑删除标记不出网)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentPromptTemplate.class)
public class AgentPromptTemplateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long workspaceId;

    private String name;

    private String description;

    private String category;

    private String content;

    /** 变量契约 JSON(驼峰;修复此前前端读蛇形恒空的错位) */
    private String variableSchema;

    private Long stableVersionId;

    /** draft / published */
    private String status;

    private Long createBy;

    private Date createTime;

    private Date updateTime;
}
