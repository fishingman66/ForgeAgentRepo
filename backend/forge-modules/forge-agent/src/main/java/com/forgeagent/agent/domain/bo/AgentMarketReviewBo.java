package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.common.core.validate.AddGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 市场评分对象
 *
 * @author ForgeAgent
 */
@Data
public class AgentMarketReviewBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 安装记录ID(每个安装记录限评一次,重复提交视为改评)
     */
    @NotNull(message = "安装记录ID不能为空", groups = {AddGroup.class})
    private Long installId;

    /**
     * 星数(1-5)
     */
    @NotNull(message = "评分星数不能为空", groups = {AddGroup.class})
    @Min(value = 1, message = "评分须为 1-5 星", groups = {AddGroup.class})
    @Max(value = 5, message = "评分须为 1-5 星", groups = {AddGroup.class})
    private Integer stars;

    /**
     * 文字评价(可选)
     */
    @Size(max = 300, message = "评价不能超过 300 字符", groups = {AddGroup.class})
    private String comment;

}
