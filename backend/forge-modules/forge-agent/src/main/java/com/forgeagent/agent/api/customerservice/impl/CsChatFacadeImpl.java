package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.domain.bo.ChatSendBo;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.agent.service.run.RunCancelRegistry;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.common.core.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 客服对话契约实现(S0):复用 AgentChatServiceImpl 的完整编排链。
 *
 * <p>访客归属通过 ChatSendBo 的两个 caller 字段显式传递,控制台路径(caller 字段为空)
 * 行为完全不变;因此这里不做任何登录态模拟,也不复制对话逻辑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsChatFacadeImpl implements CsChatFacade {

    private final IAgentChatService chatService;
    private final RunCancelRegistry runCancel;
    private final AgentInfoMapper agentInfoMapper;

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

    @Override
    public boolean hasExecutableTools(Long agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            AgentInfo agent = agentInfoMapper.selectById(agentId);
            return agent != null
                && (StringUtils.isNotBlank(agent.getToolIdsStr())
                || StringUtils.isNotBlank(agent.getMcpIdsStr()));
        } catch (Exception e) {
            log.warn("[CS] 查询 Agent 工具配置失败 agentId={}: {}", agentId, e.getMessage());
            return false;
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
        bo.setPrefetched(request.prefetched());
        bo.setCsGuardContext(request.guardContext());
        return bo;
    }
}
