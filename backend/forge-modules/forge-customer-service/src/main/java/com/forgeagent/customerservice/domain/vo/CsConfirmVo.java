package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 访客确认解决结果视图对象(CsVisitorController.confirm)
 *
 * @author ForgeAgent
 */
@Data
public class CsConfirmVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 确认后的工单状态(RESOLVED) */
    private String status;
}
