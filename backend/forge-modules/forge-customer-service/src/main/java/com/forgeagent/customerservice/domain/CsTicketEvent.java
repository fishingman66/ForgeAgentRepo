package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 工单事件轨迹 agent_cs_ticket_event(C1),只追加不修改。 */
@Data
@TableName("agent_cs_ticket_event")
public class CsTicketEvent {
    @TableId private Long id;
    private String eventType, fromStatus, toStatus, actorType, actorId, note, traceId;
    private Long ticketId;
    private Date createTime;
}
