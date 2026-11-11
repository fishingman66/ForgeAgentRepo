package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Token 用量明细对象 agent_token_usage
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_token_usage")
public class AgentTokenUsage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * Agent ID
     */
    private Long agentId;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * 回合请求ID(同一回合多次调用共享)
     */
    private String roundRequestId;

    /**
     * 发起人用户ID
     */
    private Long userId;

    /**
     * 来源(chat对话/api接口/debug调试)
     */
    private String source;

    /**
     * API Key ID(API调用时)
     */
    private Long apiKeyId;

    /**
     * 模型配置ID
     */
    private Long modelId;

    /**
     * 模型标识
     */
    private String modelName;

    /**
     * 输入token数
     */
    private Integer promptTokens;

    /**
     * 输出token数
     */
    private Integer completionTokens;

    /**
     * 总token数
     */
    private Integer totalTokens;

    /**
     * 是否估算(0实测 1估算)
     */
    private String estimated;

    /**
     * 是否成功(0成功 1失败)
     */
    private String success;

    /**
     * 调用时间
     */
    private LocalDateTime callTime;

    /**
     * 记录写入时间
     */
    private LocalDateTime createTime;

}
