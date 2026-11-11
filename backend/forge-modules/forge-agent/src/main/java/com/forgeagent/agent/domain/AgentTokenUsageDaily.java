package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Token 用量日汇总对象 agent_token_usage_daily
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_token_usage_daily")
public class AgentTokenUsageDaily implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 统计日期
     */
    private LocalDate statDate;

    /**
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * Agent ID
     */
    private Long agentId;

    /**
     * 模型标识
     */
    private String modelName;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 来源(chat/api/debug)
     */
    private String source;

    /**
     * 是否估算(0实测 1估算)
     */
    private String estimated;

    /**
     * 输入token合计
     */
    private Integer promptTokens;

    /**
     * 输出token合计
     */
    private Integer completionTokens;

    /**
     * 总token合计
     */
    private Integer totalTokens;

    /**
     * 调用次数
     */
    private Integer callCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
