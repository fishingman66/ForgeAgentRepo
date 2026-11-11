package com.forgeagent.agent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentChatAttachment;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.domain.AgentDatasourceExample;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.domain.AgentTool;
import com.forgeagent.agent.domain.bo.ChatSendBo;
import com.forgeagent.agent.domain.bo.AgentDraftBo;
import com.forgeagent.agent.domain.vo.AgentMessageVo;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agent.mapper.AgentMcpServerMapper;
import com.forgeagent.agent.mapper.AgentDatasourceExampleMapper;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agent.mapper.AgentProviderMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.agent.mapper.AgentTokenUsageMapper;
import com.forgeagent.agent.mapper.AgentToolMapper;
import com.forgeagent.agent.service.AgentChatAttachmentService;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agent.service.AgentRunner;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.memory.service.IAgentMemoryService;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.agent.service.TokenUsageRecorder;
import com.forgeagent.rag.service.document.TokenEstimator;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.forgeagent.agentcore.service.UtilityAgents;
import com.forgeagent.agentcore.utils.ChatTexts;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 对话 SSE 流式支持(事件发射 / 客户端断连检测 / 中断 / 消息脱敏)
 *
 * <p>自 AgentChatServiceImpl 拆出,无状态工具型协作 Bean。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class ChatStreamSupport {
    /**
     * 断连时中断上游 AgentScope 执行(@Lazy 规避 runner 装配链上的潜在循环依赖)
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private com.forgeagent.agent.service.runner.AgentScopeRunner agentScopeRunner;

    /**
     * 判断异常是否由客户端断连引起(可能被 reactor 包装,沿 cause 链检查)
     */
    public boolean isClientDisconnect(Throwable e) {
        while (e != null) {
            if (e instanceof java.io.UncheckedIOException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    /**
     * 静默中断指定会话的上游执行(失败不影响收尾)
     */
    public void interruptQuietly(Long sessionId) {
        try {
            agentScopeRunner.interrupt(sessionId);
        } catch (Exception ignore) {
            // 中断失败不影响收尾
        }
    }

    public void emitSse(StreamEmitter emitter, String event, Object data) {
        try {
            emitter.send(event, data);
        } catch (java.io.UncheckedIOException e) {
            // 客户端断开:抛出交由 doStream 统一识别并中断上游执行
            throw e;
        } catch (Exception ignore) {
            // 其他发送失败直接忽略
        }
    }

    public String safeMessage(Exception e) {
        return e.getMessage() == null ? "执行失败" : e.getMessage();
    }
}