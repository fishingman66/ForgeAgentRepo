package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 工作流对外 API 端点(G2)
 *
 * <p>把已编排的工作流暴露为外部可调用的同步 HTTP 端点;鉴权挂空间 API 密钥体系
 * (明文 ak- 密钥仅创建时返回,库存 SHA-256),限流按端点每分钟计数。</p>
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_workflow_endpoint")
public class AgentWorkflowEndpoint extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * AgentID(工作流归属)
     */
    private Long agentId;

    /**
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * 端点编码(路径段,全局唯一)
     */
    private String endpointCode;

    /**
     * 端点名称
     */
    private String name;

    /**
     * 绑定的 API 密钥ID(空为仅空间内调试)
     */
    private Long apiKeyId;

    /**
     * 每分钟调用上限
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

    /**
     * 删除标志(0存在 其他删除)
     */
    @TableLogic
    private Long delFlag;
}
