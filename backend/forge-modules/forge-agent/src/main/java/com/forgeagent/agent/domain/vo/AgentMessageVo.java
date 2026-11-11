package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentMessage;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 会话消息视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMessage.class)
public class AgentMessageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
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
     * 扩展信息
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
    private Date feedbackTime;

    /**
     * 引用溯源(知识库召回命中 JSON 数组)
     */
    private String citations;

    /**
     * 图片附件(JSON数组)
     */
    private String attachments;

    /**
     * 创建时间
     */
    private Date createTime;

}
