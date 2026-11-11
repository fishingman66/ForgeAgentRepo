package com.forgeagent.agent.api.customerservice;

import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.service.StreamEmitter;

/**
 * 智能客服 → Agent 执行域的对话契约(S0)。
 *
 * <p>客服访客没有 Sa-Token 登录态,故归属信息经 {@link CsChatRequest} 显式传入,
 * 由 {@code ChatSendBo.callerUserId / callerWorkspaceId} 在对话链内旁路登录态校验;
 * 不使用 ThreadLocal 或 SPI,避免流式执行跨线程时上下文丢失。</p>
 *
 * <p>实现位于 forge-agent 模块, forge-customer-service 只依赖本接口。</p>
 */
public interface CsChatFacade {

    /** 阻塞式一轮对话(客服机器人自助 / 管理端兜底)。 */
    ChatSendVo send(CsChatRequest request);

    /** 流式一轮对话,事件经 StreamEmitter 下发(与控制台聊天页同一套事件语义)。 */
    void stream(CsChatRequest request, StreamEmitter emitter);

    /** 中断指定会话正在进行的生成(写跨实例取消令牌)。 */
    void interrupt(Long sessionId);

    /**
     * 客服对话请求。
     *
     * @param sessionId          客服会话对应的 agent_session.id(新建时为空)
     * @param agentId            客服机器人 AgentID(sessionId 为空时必填)
     * @param content            访客消息
     * @param callerUserId       渠道服务账号用户ID,非空时旁路 LoginHelper
     * @param callerWorkspaceId  渠道归属空间ID,非空时用 ResourceAccessGuard 纯函数校验
     * @param visitorId          访客ID(Trace / 归属留痕)
     * @param channelCode        渠道编码
     * @param ticketId           关联工单ID(可空)
     */
    record CsChatRequest(Long sessionId, Long agentId, String content,
                         Long callerUserId, Long callerWorkspaceId,
                         Long visitorId, String channelCode, Long ticketId) {

        public static CsChatRequest of(Long sessionId, Long agentId, String content,
                                      Long callerUserId, Long callerWorkspaceId) {
            return new CsChatRequest(sessionId, agentId, content, callerUserId,
                callerWorkspaceId, null, null, null);
        }
    }
}
