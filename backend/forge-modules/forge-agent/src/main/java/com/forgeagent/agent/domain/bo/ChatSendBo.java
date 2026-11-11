package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 聊天发送业务对象
 *
 * @author ForgeAgent
 */
@Data
public class ChatSendBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话ID(为空则用 agentId 自动建会话)
     */
    private Long sessionId;

    /**
     * AgentID(sessionId 为空时必填)
     */
    private Long agentId;

    /**
     * 消息内容(允许为空以支持纯图片消息)
     */
    @Size(max = 4000, message = "消息内容不能超过 4000 字符")
    private String content;

    /**
     * 图片附件ID列表
     */
    private List<Long> attachmentIds;

    /**
     * 客服场景(S0):渠道服务账号用户ID。非空时对话链旁路 LoginHelper,
     * 以该 ID 作为会话归属;控制台聊天页不传,行为完全不变。
     */
    private Long callerUserId;

    /**
     * 客服场景(S0):渠道归属空间ID。非空时用 ResourceAccessGuard 纯函数校验空间,
     * 不依赖 Sa-Token 上下文(免登录链路越权防线)。
     */
    private Long callerWorkspaceId;

    /**
     * 调试会话ID(UX-01):非空时进入调试模式,复用该临时会话;为空且 agentDraft 非空则新建临时会话
     */
    private Long debugSessionId;

    /**
     * 调试草稿(UX-01):非空时覆盖 Agent 配置用于本轮执行,不写回 agent_info
     */
    private AgentDraftBo agentDraft;

}
