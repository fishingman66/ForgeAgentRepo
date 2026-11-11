package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.ChatSendBo;
import com.forgeagent.agent.domain.vo.ChatSendVo;

/**
 * Agent 聊天 Service 接口
 *
 * @author ForgeAgent
 */
public interface IAgentChatService {

    ChatSendVo send(ChatSendBo bo);

    /**
     * 以指定用户身份发起一轮对话(供 Cron/Webhook 等无登录上下文场景使用)
     *
     * @param agentId        AgentID
     * @param content        消息内容
     * @param operatorUserId 操作人用户ID(触发器归属人)
     */
    ChatSendVo sendAsUser(Long agentId, String content, Long operatorUserId);

    /**
     * SSE 流式发送:经 emitter 依次推送 meta(会话定位)/delta(增量文本)/done(最终消息)/error 事件
     *
     * @param bo      发送内容
     * @param emitter SSE 事件发射器(Controller 以 Servlet 输出流实现)
     */
    void streamSend(ChatSendBo bo, StreamEmitter emitter);

    /**
     * SSE 流式调试发送(UX-01):复用临时会话,按 draft 覆盖 Agent 配置执行,不写回 agent_info;
     * 调试会话 token 计入用户配额,但不写「对话发送」审计事件
     *
     * @param bo      发送内容(debugSessionId/agentDraft 必传)
     * @param emitter SSE 事件发射器
     */
    void streamDebugSend(ChatSendBo bo, StreamEmitter emitter);

    /**
     * 重新生成会话最后一条助手回复:删除原回复后基于其前的用户消息重新执行一轮对话
     *
     * @param sessionId 会话ID
     * @return 原用户消息与新助手回复
     */
    ChatSendVo regenerate(Long sessionId);

    /**
     * SSE 流式重新生成最后一条助手回复(事件序列与 streamSend 一致,无 meta 事件)
     *
     * @param sessionId 会话ID
     * @param emitter   SSE 事件发射器
     */
    void streamRegenerate(Long sessionId, StreamEmitter emitter);

    /**
     * 消息反馈(点赞/点踩,传空取消),供运营看板聚合满意度
     *
     * @param messageId 消息ID(须为助手消息)
     * @param feedback  like / dislike / 空(取消)
     * @param comment   反馈备注(可空,取消反馈时忽略)
     * @param tags      反馈标签(逗号分隔,可空,取消反馈时忽略)
     */
    void feedback(Long messageId, String feedback, String comment, String tags);

    /**
     * 撤回最后一条用户消息:删除该消息及其后的助手回复,并回退会话计数
     *
     * @param messageId 用户消息ID(须为会话末轮消息)
     */
    void recallMessage(Long messageId);
}
