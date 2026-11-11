package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * Agent 会话消息对象 agent_message
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_message")
public class AgentMessage extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * 角色(user/assistant)
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 扩展信息(mock执行元数据)
     */
    private String extJson;

    /**
     * 用户反馈(like 点赞 / dislike 点踩,空为未反馈)
     */
    private String feedback;

    /**
     * 反馈备注
     */
    private String feedbackComment;

    /**
     * 反馈标签(逗号分隔)
     */
    private String feedbackTags;

    /**
     * 反馈时间
     */
    private java.util.Date feedbackTime;

    /**
     * 引用溯源(知识库召回命中 JSON 数组)
     */
    private String citations;

    /**
     * 图片附件(JSON数组)
     */
    private String attachments;

    /**
     * 客服场景(C0):客户端幂等键
     */
    private String clientMsgId;

    /**
     * 客服场景(C0):发送者类型 user/assistant/seat/system
     */
    private String senderType;

    /**
     * 客服场景(C0):关联工单ID
     */
    private Long ticketId;

}
