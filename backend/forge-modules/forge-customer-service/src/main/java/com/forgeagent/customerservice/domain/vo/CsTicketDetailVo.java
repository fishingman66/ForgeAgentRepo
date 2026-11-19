package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 坐席工单详情视图对象(CsSeatController.ticket:工单 + 事件流)
 *
 * @author ForgeAgent
 */
@Data
public class CsTicketDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private com.forgeagent.customerservice.domain.vo.CsTicketVo ticket;

    private List<com.forgeagent.customerservice.domain.vo.CsTicketEventVo> events;
}
