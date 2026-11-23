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
    /** A3:@提及的目标坐席(仅 MENTION 事件有值)。 */
    private Long targetUserId;
    /** A-5:0 未读 / 1 已读;DDL 默认 0,写入侧显式置 false 避免依赖时序。 */
    private Boolean readFlag;
    private Long ticketId;
    private Date createTime;
}
