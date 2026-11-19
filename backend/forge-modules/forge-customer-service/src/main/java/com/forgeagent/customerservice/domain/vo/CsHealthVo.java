package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 健康自检视图对象(CsHealthController.health)
 *
 * @author ForgeAgent
 */
@Data
public class CsHealthVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String module;

    private Boolean enabled;

    /** 传输通道(sse/ws) */
    private String transport;

    /** 当前阶段 */
    private String stage;
}
