package com.forgeagent.callcenter.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 语音渠道启停入参(0 启用 / 1 停用)
 *
 * @author ForgeAgent
 */
@Data
public class CsCallChannelStatusBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标状态(0启用 1停用)
     */
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^[01]$", message = "状态仅允许 0(启用)/1(停用)")
    private String status;

}
