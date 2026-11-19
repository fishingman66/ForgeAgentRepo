package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsTicketEvent;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 工单事件视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsTicketEvent.class)
public class CsTicketEventVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String actorType;
    private String actorId;
    private String note;
    private String traceId;
    private Long ticketId;
    private Date createTime;
}
