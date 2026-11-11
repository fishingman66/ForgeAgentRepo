package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模型配置业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentModel.class, reverseConvertGenerate = false)
public class AgentModelBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "模型ID不能为空", groups = {EditGroup.class})
    private Long id;
    /**
     * 归属工作空间(NULL=平台共享,全空间可见;非空=空间私有,仅归属空间可见)
     */
    private Long workspaceId;
    /**
     * 是否创建为平台共享资源(仅超管可选;false 时 workspaceId 必填)
     */
    private Boolean shared;



    /**
     * 模型配置名称
     */
    @NotBlank(message = "模型名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 50, message = "模型名称长度须在 2-50 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 提供商
     */
    @NotBlank(message = "提供商不能为空", groups = {AddGroup.class, EditGroup.class})
    private String provider;

    /**
     * API基础地址
     */
    @Size(max = 200, message = "API基础地址不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String baseUrl;

    /**
     * API密钥(编辑时留空表示不修改)
     */
    @Size(max = 500, message = "API密钥不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String apiKey;

    /**
     * 模型标识
     */
    @NotBlank(message = "模型标识不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "模型标识不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String modelName;

    /**
     * 采样温度(0-2)
     */
    @DecimalMin(value = "0", message = "温度不能小于 0", groups = {AddGroup.class, EditGroup.class})
    @DecimalMax(value = "2", message = "温度不能大于 2", groups = {AddGroup.class, EditGroup.class})
    private BigDecimal temperature;

    /**
     * 最大输出tokens
     */
    @NotNull(message = "最大tokens不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 1, message = "最大tokens须大于 0", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 100000, message = "最大tokens不能超过 100000", groups = {AddGroup.class, EditGroup.class})
    private Integer maxTokens;

    /**
     * 上下文窗口(tokens,留空表示不展示上下文剩余)
     */
    @Min(value = 1024, message = "上下文窗口不能小于 1024", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 10000000, message = "上下文窗口不能超过 10000000", groups = {AddGroup.class, EditGroup.class})
    private Integer contextWindow;

    /**
     * 输入单价(每百万token,非必填)
     */
    @DecimalMin(value = "0", message = "输入单价不能为负", groups = {AddGroup.class, EditGroup.class})
    private BigDecimal priceInput;

    /**
     * 输出单价(每百万token,非必填)
     */
    @DecimalMin(value = "0", message = "输出单价不能为负", groups = {AddGroup.class, EditGroup.class})
    private BigDecimal priceOutput;

    /**
     * 定价币种(默认 USD)
     */
    @Size(max = 8, message = "币种不能超过 8 字符", groups = {AddGroup.class, EditGroup.class})
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
    @Size(max = 200, message = "备注不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

    /**
     * 查询关键字(仅查询用)
     */
    @Size(max = 50, groups = {QueryGroup.class})
    private String keyword;

}
