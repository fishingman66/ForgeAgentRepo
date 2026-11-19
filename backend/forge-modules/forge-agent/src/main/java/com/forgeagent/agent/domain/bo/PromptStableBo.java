package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Prompt 模板稳定版设置业务对象
 *
 * @author ForgeAgent
 */
@Data
public class PromptStableBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "templateId 不能为空")
    private Long templateId;

    @NotNull(message = "versionId 不能为空")
    private Long versionId;
}
