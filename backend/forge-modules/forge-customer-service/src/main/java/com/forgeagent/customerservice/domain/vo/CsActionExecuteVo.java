package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 建议动作执行结果视图对象(CsAssistActionService.execute)
 *
 * @author ForgeAgent
 */
@Data
public class CsActionExecuteVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long suggestionId;

    /** 动作编码 */
    private String code;

    private Boolean executed;

    private String message;

    /** 联动产生的工单号(可空) */
    private Long ticketId;

    /** 执行明细(可空) */
    private String detail;
}
