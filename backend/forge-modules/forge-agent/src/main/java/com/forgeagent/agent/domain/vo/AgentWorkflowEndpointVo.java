package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 工作流对外 API 端点视图对象(G2)
 *
 * @author ForgeAgent
 */
@Data
public class AgentWorkflowEndpointVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long agentId;

    /** Agent 名称(展示用) */
    private String agentName;

    private Long workspaceId;

    private String endpointCode;

    /** 调用地址(相对路径,外部以 POST /workflow-api/{code} 访问) */
    private String invokePath;

    private String name;

    private Long apiKeyId;

    /** 绑定密钥脱敏展示(ak-****) */
    private String maskedKey;

    private Integer rateLimit;

    private Integer timeoutSeconds;

    /** 状态(0启用 1停用) */
    private String status;

    private Date createTime;
}
