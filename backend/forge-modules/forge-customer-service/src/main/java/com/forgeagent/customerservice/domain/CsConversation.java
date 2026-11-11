package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 客服会话 agent_cs_conversation(C0),持有 agent_session.id 复用完整对话链。 */
@Data
@TableName("agent_cs_conversation")
public class CsConversation {
    @TableId private Long id;
    private String channelCode, status;
    private Long agentSessionId, visitorId, workspaceId, agentId, ticketId;
    /** P3:是否已确定接待 Agent(1 已确定,不再路由);建会话时默认 0。 */
    private String agentRouted;
    private Date lastVisitorAt, lastSeatAt, csatInvitedAt, createTime, updateTime;
}
