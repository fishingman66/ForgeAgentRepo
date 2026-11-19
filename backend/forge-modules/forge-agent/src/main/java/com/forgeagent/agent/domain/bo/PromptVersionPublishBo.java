package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Prompt 模板版本发布业务对象
 *
 * @author ForgeAgent
 */
@Data
public class PromptVersionPublishBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "templateId 不能为空")
    private Long templateId;

    @NotBlank(message = "模板内容不能为空")
    private String content;

    private String variableSchema;

    private String remark;

    /** true=同步设为模板稳定版 */
    private Boolean asStable;
}
