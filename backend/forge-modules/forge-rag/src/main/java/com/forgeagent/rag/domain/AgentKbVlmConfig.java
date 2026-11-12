package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库图片识别模型配置 agent_kb_vlm_config(按工作空间)
 *
 * <p>workspace_id=0 表示平台默认;解析优先级:工作空间行 > 平台默认行 > 全局 rag.knowledge.vlm-model。
 * api_key_cipher 只存密文,任何接口不回显明文。</p>
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_vlm_config")
public class AgentKbVlmConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 工作空间ID;0=平台默认
     */
    private Long workspaceId;

    /**
     * PLATFORM(复用平台 agent_model)/ CUSTOM(OpenAI 兼容自定义)
     */
    private String sourceType;

    /**
     * 模型标识(PLATFORM 时为 agent_model.model_name)
     */
    private String modelName;

    /**
     * CUSTOM 模式基础地址
     */
    private String baseUrl;

    /**
     * API Key 密文(ENC: 前缀)
     */
    private String apiKeyCipher;

    /**
     * 是否启用
     */
    private Integer enabled;

    private Long updateBy;

    private Date createTime;

    private Date updateTime;
}
