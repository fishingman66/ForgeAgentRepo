package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 客服工单 agent_cs_ticket(C1),七态状态由 CsTicketService 统一收敛。 */
@Data
@TableName("agent_cs_ticket")
public class CsTicket {
    @TableId private Long id;
    private String ticketNo, channelCode, title, category, priority, status;
    private String seatGroup, handoffReason, resolveNote;
    private Long conversationId, sessionId, visitorId, workspaceId, agentId, assigneeId;
    private Integer reopenCount;
    private Long firstResponseMs, botFirstTokenMs;
    private Date waitingSince, resolvedAt, createTime, updateTime;
}
