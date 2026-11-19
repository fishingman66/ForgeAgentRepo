package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Prompt 编译预览业务对象
 *
 * @author ForgeAgent
 */
@Data
public class PromptPreviewBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "versionId 不能为空")
    private Long versionId;

    /** 变量值 JSON 串 */
    private String variables;
}
