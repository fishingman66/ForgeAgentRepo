package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 会话 Token 用量实时反馈视图对象(UX-03)
 *
 * <p>contextWindow 未配置时 contextUsed/contextRemainingPercent 为 null,前端隐藏进度条;
 * 模型未配置定价时 costEstimate 为 null。</p>
 *
 * @author ForgeAgent
 */
@Data
public class AgentSessionUsageBarVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * 本会话累计 Token(输入+输出)
     */
    private Long totalTokens;

    /**
     * 累计输入 Token
     */
    private Long promptTokens;

    /**
     * 累计输出 Token
     */
    private Long completionTokens;

    /**
     * 最近一轮消耗 Token(无调用记录时为 0)
     */
    private Long lastRoundTokens;

    /**
     * 模型上下文窗口(tokens,未配置为 null)
     */
    private Integer contextWindow;

    /**
     * 上下文已占用 tokens(以最近一轮输入量近似,无记录或未配置窗口时为 null)
     */
    private Long contextUsed;

    /**
     * 上下文剩余百分比(0-100,未配置窗口时为 null)
     */
    private Double contextRemainingPercent;

    /**
     * 累计成本估算(模型未配置定价时为 null)
     */
    private BigDecimal costEstimate;

    /**
     * 定价币种(默认 USD)
     */
    private String currency;

}
