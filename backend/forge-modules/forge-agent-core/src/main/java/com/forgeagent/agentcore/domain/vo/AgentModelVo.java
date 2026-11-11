package com.forgeagent.agentcore.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agentcore.domain.AgentModel;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型配置视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentModel.class)
public class AgentModelVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;
    /**
     * 归属工作空间(NULL=平台共享,全空间可见;非空=空间私有,仅归属空间可见)
     */
    private Long workspaceId;


    /**
     * 模型配置名称
     */
    private String name;

    /**
     * 提供商
     */
    private String provider;

    /**
     * API基础地址
     */
    private String baseUrl;

    /**
     * API密钥(脱敏)
     */
    private String apiKey;

    /**
     * 模型标识
     */
    private String modelName;

    /**
     * 采样温度
     */
    private BigDecimal temperature;

    /**
     * 最大输出tokens
     */
    private Integer maxTokens;

    /**
     * 上下文窗口(tokens)
     */
    private Integer contextWindow;

    /**
     * 输入单价(每百万token)
     */
    private BigDecimal priceInput;

    /**
     * 输出单价(每百万token)
     */
    private BigDecimal priceOutput;

    /**
     * 定价币种
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
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
