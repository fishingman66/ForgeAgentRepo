package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 图片识别模型配置视图(按工作空间):API Key 只回显掩码,绝不回传明文
 *
 * @author ForgeAgent
 */
@Data
public class KbVlmConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long workspaceId;

    /**
     * PLATFORM / CUSTOM
     */
    private String sourceType;

    private String modelName;

    private String baseUrl;

    /**
     * 掩码后的 Key(如 ****abcd);未配置为空
     */
    private String apiKeyMasked;

    /**
     * 是否已配置 Key
     */
    private Boolean hasApiKey;

    private Integer enabled;

    private Date updateTime;

    /**
     * 当前生效来源:WORKSPACE / PLATFORM_DEFAULT / GLOBAL / NONE(便于界面提示"实际用的是哪一份")
     */
    private String effectiveSource;
}
