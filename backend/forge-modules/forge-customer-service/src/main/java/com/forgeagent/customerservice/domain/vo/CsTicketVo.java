package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsTicket;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 坐席工单视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsTicket.class)
public class CsTicketVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String ticketNo;
    private String channelCode;
    private String title;
    private String category;
    private String priority;
    private String status;
    private String seatGroup;
    private String handoffReason;
    private String resolveNote;
    private Long conversationId;
    private Long sessionId;
    private Long visitorId;
    private Long workspaceId;
    private Long agentId;
    private Long assigneeId;
    private Integer reopenCount;
    private Long firstResponseMs;
    private Long botFirstTokenMs;
    private Date waitingSince;
    private Date resolvedAt;
    private Date createTime;
    private Date updateTime;
}
