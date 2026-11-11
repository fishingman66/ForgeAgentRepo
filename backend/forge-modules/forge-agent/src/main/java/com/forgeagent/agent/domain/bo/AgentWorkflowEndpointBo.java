package com.forgeagent.agent.domain.bo;

import com.forgeagent.agent.domain.AgentWorkflowEndpoint;
import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作流对外 API 端点业务对象(G2)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentWorkflowEndpoint.class, reverseConvertGenerate = false)
public class AgentWorkflowEndpointBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "端点ID不能为空")
    private Long id;

    /**
     * AgentID(工作流归属)
     */
    @NotNull(message = "AgentID不能为空")
    private Long agentId;

    /**
     * 端点编码(路径段,全局唯一,小写字母数字连字符)
     */
    @NotBlank(message = "端点编码不能为空")
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,62}$", message = "端点编码须为 3-63 位小写字母/数字/连字符,且以字母或数字开头")
    private String endpointCode;

    /**
     * 端点名称
     */
    @Size(max = 100, message = "端点名称不能超过 100 字符")
    private String name;

    /**
     * 绑定的 API 密钥ID(空为仅空间内调试)
     */
    private Long apiKeyId;

    /**
     * 每分钟调用上限(1-6000)
     */
    private Integer rateLimit;

    /**
     * 同步执行超时秒数(1-300)
     */
    private Integer timeoutSeconds;

    /**
     * 状态(0启用 1停用)
     */
    private String status;
}
