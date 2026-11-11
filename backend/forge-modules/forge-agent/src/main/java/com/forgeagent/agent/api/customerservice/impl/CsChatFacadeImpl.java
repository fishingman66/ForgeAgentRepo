package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.domain.bo.ChatSendBo;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.agent.service.run.RunCancelRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 客服对话契约实现(S0):复用 AgentChatServiceImpl 的完整编排链。
 *
 * <p>访客归属通过 ChatSendBo 的两个 caller 字段显式传递,控制台路径(caller 字段为空)
 * 行为完全不变;因此这里不做任何登录态模拟,也不复制对话逻辑。</p>
 */
@Service
@RequiredArgsConstructor
public class CsChatFacadeImpl implements CsChatFacade {

    private final IAgentChatService chatService;
    private final RunCancelRegistry runCancel;

    @Override
    public ChatSendVo send(CsChatRequest request) {
        return chatService.send(toBo(request));
    }

    @Override
    public void stream(CsChatRequest request, StreamEmitter emitter) {
        chatService.streamSend(toBo(request), emitter);
    }

    @Override
    public void interrupt(Long sessionId) {
        if (sessionId != null) {
            runCancel.requestCancel(sessionId);
        }
    }

    private ChatSendBo toBo(CsChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("客服对话请求不能为空");
        }
        if (request.callerUserId() == null) {
            throw new IllegalArgumentException("客服对话必须指定渠道服务账号 callerUserId");
        }
        ChatSendBo bo = new ChatSendBo();
        bo.setSessionId(request.sessionId());
        bo.setAgentId(request.agentId());
        bo.setContent(request.content());
        bo.setCallerUserId(request.callerUserId());
        bo.setCallerWorkspaceId(request.callerWorkspaceId());
        return bo;
    }
}
