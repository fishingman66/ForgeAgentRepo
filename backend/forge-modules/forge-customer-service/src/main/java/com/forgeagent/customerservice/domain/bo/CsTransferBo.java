package com.forgeagent.customerservice.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工单转接业务对象(assigneeId 为空=转回公共池)
 *
 * @author ForgeAgent
 */
@Data
public class CsTransferBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标坐席用户ID(空=转入公共池) */
    private Long assigneeId;

    private String note;
}
