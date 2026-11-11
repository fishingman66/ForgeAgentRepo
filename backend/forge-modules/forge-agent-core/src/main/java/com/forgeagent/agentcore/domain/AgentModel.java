package com.forgeagent.agentcore.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * Agent 模型配置对象 agent_model
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_model")
public class AgentModel extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;
    /**
     * 归属工作空间(NULL=平台共享,全空间可见;非空=空间私有,仅归属空间可见)
     */
    private Long workspaceId;


    /**
     * 模型配置名称(全局唯一)
     */
    private String name;

    /**
     * 提供商(openai/anthropic/dashscope/ollama/custom)
     */
    private String provider;

    /**
     * API基础地址
     */
    private String baseUrl;

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * 模型标识(如 gpt-4o/qwen-plus)
     */
    private String modelName;

    /**
     * 采样温度(0-2)
     */
    private BigDecimal temperature;

    /**
     * 最大输出tokens
     */
    private Integer maxTokens;

    /**
     * 上下文窗口(tokens),未配置时会话页不展示剩余进度
     */
    private Integer contextWindow;

    /**
     * 输入单价(每百万token,币种见 priceCurrency)
     */
    private BigDecimal priceInput;

    /**
     * 输出单价(每百万token,币种见 priceCurrency)
     */
    private BigDecimal priceOutput;

    /**
     * 定价币种(默认 USD)
     */
    private String priceCurrency;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 支持图片输入(0否 1是)
     */
    private String supportVision;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
