package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 配额现状视图对象(CsAssistController.quota;字段对齐前端 CsQuotaView)
 *
 * @author ForgeAgent
 */
@Data
public class CsQuotaVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean enabled;

    /** 超限处置(handoff/deny) */
    private String action;

    /** ALLOW/WARN/BLOCK */
    private String level;

    private Long used;

    private Long limit;
}
