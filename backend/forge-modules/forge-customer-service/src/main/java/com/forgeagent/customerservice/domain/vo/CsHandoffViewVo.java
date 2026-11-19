package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 访客转人工结果视图对象(CsTicketService.handoffView)
 *
 * @author ForgeAgent
 */
@Data
public class CsHandoffViewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long ticketId;

    private String ticketNo;

    /** 工单状态(PENDING/PROCESSING/...) */
    private String status;

    private Date waitingSince;
}
