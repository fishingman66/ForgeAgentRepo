package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 聊天发送结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class ChatSendVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * 用户消息
     */
    private AgentMessageVo userMessage;

    /**
     * 助手回复
     */
    private AgentMessageVo assistantMessage;

}
