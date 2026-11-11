package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话内 Token 消耗视图对象
 *
 * @author ForgeAgent
 */
@Data
public class AgentSessionUsageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * 累计输入 Token
     */
    private Long promptTokens;

    /**
     * 累计输出 Token
     */
    private Long completionTokens;

    /**
     * 累计总 Token
     */
    private Long totalTokens;

    /**
     * 模型调用次数
     */
    private Long callCount;

    /**
     * Token 预算(0=未配置)
     */
    private Long tokenBudget;

    /**
     * 预算占用百分比(0-100,无预算时为 null)
     */
    private Double budgetUsage;

}
