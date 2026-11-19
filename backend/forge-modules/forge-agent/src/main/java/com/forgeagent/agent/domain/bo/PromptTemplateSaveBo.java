package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Prompt 模板新建/编辑业务对象(id 为空=新建)
 *
 * @author ForgeAgent
 */
@Data
public class PromptTemplateSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 为空=新建,非空=编辑 */
    private Long id;

    @NotBlank(message = "模板内容不能为空")
    private String name;

    private String description;

    /** 缺省 system */
    private String category;

    @NotBlank(message = "模板内容不能为空")
    private String content;

    /** 变量契约 JSON */
    private String variableSchema;

    @NotNull(message = "工作空间不能为空")
    private Long workspaceId;
}
