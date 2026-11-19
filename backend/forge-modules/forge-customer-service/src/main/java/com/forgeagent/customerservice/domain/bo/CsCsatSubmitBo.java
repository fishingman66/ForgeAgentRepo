package com.forgeagent.customerservice.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 访客满意度提交业务对象
 *
 * @author ForgeAgent
 */
@Data
public class CsCsatSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    private Integer score;

    /** 评价内容(可空) */
    private String comment;

    /** 标签(可空,JSON 串或逗号分隔) */
    private String tags;
}
