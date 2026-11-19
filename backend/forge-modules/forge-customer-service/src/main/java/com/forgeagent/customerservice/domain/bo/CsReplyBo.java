package com.forgeagent.customerservice.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 坐席回复业务对象
 *
 * @author ForgeAgent
 */
@Data
public class CsReplyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "回复内容不能为空")
    private String content;
}
