package com.forgeagent.rag.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 图片识别模型配置入参
 *
 * @author ForgeAgent
 */
@Data
public class KbVlmConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作空间ID;0=平台默认
     */
    @NotNull(message = "工作空间不能为空")
    private Long workspaceId;

    /**
     * PLATFORM(复用平台 agent_model)/ CUSTOM(OpenAI 兼容)
     */
    private String sourceType;

    private String modelName;

    private String baseUrl;

    /**
     * API Key 明文;编辑时留空表示沿用已存密文
     */
    private String apiKey;

    private Integer enabled;
}
