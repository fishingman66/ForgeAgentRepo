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
    /** B1:最近一轮意图编码(规则命中,未命中为 NULL)。 */
    private String lastIntentCode;
    /** B1:访客负面情绪连续轮数(得分不超阈值递增,超阈值清零)。 */
    private Integer sentimentStreak;
    /** B1:最近一轮访客情绪分([-1,1],负面为负)。 */
    private java.math.BigDecimal lastSentimentScore;
    private Date lastVisitorAt, lastSeatAt, csatInvitedAt, createTime, updateTime;
}
