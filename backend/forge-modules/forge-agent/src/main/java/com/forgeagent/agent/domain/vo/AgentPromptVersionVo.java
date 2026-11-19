package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentPromptVersion;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Prompt 模板不可变版本视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentPromptVersion.class)
public class AgentPromptVersionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long templateId;

    private Integer versionNo;

    private String content;

    /** 变量契约 JSON(驼峰;修复此前前端读蛇形恒空的错位) */
    private String variableSchema;

    private String contentHash;

    private String remark;

    private Long createBy;

    private Date createTime;
}
