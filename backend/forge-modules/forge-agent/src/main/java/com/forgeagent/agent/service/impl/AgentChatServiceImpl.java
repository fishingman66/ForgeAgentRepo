package com.forgeagent.agent.service.impl;

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
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agent.service.IAgentWorkflowService;
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
import com.forgeagent.agentcore.service.UtilityAgents;
import com.forgeagent.agentcore.utils.ChatTexts;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agent.service.support.AnswerCitationValidator;
import com.forgeagent.agent.service.support.ChatContextAssembler;
import com.forgeagent.agent.service.support.ChatRetrievalSupport;
import com.forgeagent.agent.service.support.ChatStreamSupport;
import com.forgeagent.agent.service.support.ContextBudgetAllocator;
import com.forgeagent.agent.service.support.SessionSummaryService;
import com.forgeagent.agent.service.runner.RunWorkingMemory;
import com.forgeagent.agent.service.AgentRunner;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.memory.service.IAgentMemoryService;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.agent.service.TokenUsageRecorder;
import com.forgeagent.rag.service.document.TokenEstimator;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.agent.service.run.RunCancelRegistry;
import com.forgeagent.agent.service.run.RunTraceService;
import com.forgeagent.agent.service.run.TraceStepType;
import com.forgeagent.agent.service.governance.PromptTemplateService;
import com.forgeagent.agent.service.routing.IntentRouter;
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

/**
 * Agent 聊天 Service 实现(AgentScope 内核 + Mock 回退)
 *
 * <p>send 为整段阻塞返回;streamSend 为 SSE 流式返回(meta/delta/done/error 四类事件),
 * 两者共享会话定位、资源装配、RAG 检索、长期记忆注入与预算预检逻辑;
 * 团队协作与结构化输出在流式路径下自动降级为整段回调。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentChatServiceImpl implements IAgentChatService {

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentToolMapper toolMapper;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentConfigCacheService agentConfigCache;
    private final List<AgentRunner> agentRunners;
    private final TokenUsageRecorder usageRecorder;
    private final AgentProviderMapper providerMapper;
    private final IAgentKnowledgeService knowledgeService;
    private final KnowledgeProperties knowledgeProperties;
    private final IAgentMemoryService memoryService;
    private final AgentChatAttachmentService attachmentService;
    private final AgentChatProperties chatProperties;
    private final com.forgeagent.agent.service.semantic.SemanticContextBuilder semanticContextBuilder;
    private final AuditLogHelper auditLogHelper;

    private final ChatRetrievalSupport retrievalSupport;
    private final ChatContextAssembler contextAssembler;
    private final ChatStreamSupport streamSupport;
    private final RunTraceService runTrace;
    private final RunCancelRegistry runCancel;
    private final PromptTemplateService promptTemplateService;
    private final IntentRouter intentRouter;
    /** P3 / M1: L0 上下文预算分配、L2 滚动摘要、L1 工作记忆 */
    private final ContextBudgetAllocator contextBudgetAllocator;
    private final SessionSummaryService sessionSummaryService;
    private final RunWorkingMemory runWorkingMemory;
    /** P2/W10:对话路由到工作流执行的入口与事件缓冲 */
    private final IAgentWorkflowService workflowService;
    private final com.forgeagent.agent.service.run.RunEventBuffer workflowEventBuffer;
    /** 体检修复(H1):对话链路统一收敛空间成员校验 */
    private final com.forgeagent.agentcore.service.WorkspacePermissionHelper permissionHelper;
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;


    /**
     * 断连时中断上游 AgentScope 执行(@Lazy 规避 runner 装配链上的潜在循环依赖)
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private com.forgeagent.agent.service.runner.AgentScopeRunner agentScopeRunner;

    /**
     * 标题生成系统提示词
     */
    private static final String TITLE_SYS_PROMPT =
        "你是会话标题生成助手。根据提供的这轮对话生成一个不超过15字的简短标题,"
            + "准确概括用户的核心意图;只输出标题文本,不要引号、句号或任何其他内容。";

    /**
     * 标题生成专用执行器(daemon):单线程 + 有界队列,队列满时丢弃任务
     * (标题生成失败可回退为截断式标题,不允许拖垮对话主流程)
     */
    private static final java.util.concurrent.ThreadPoolExecutor TITLE_WORKER =
        new java.util.concurrent.ThreadPoolExecutor(1, 2, 60, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "agent-chat-title");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    /**
     * 会话执行锁键前缀:同一会话同一时刻只允许一轮对话执行,避免重复点击/多标签页
     * 造成重复模型调用与重复计费
     */
    private static final String SESSION_LOCK_PREFIX = "agent:chat:running:";


    /**
     * 一轮对话的准备结果(定位会话→装配资源→预算预检→选择执行器);
     * regen=true 表示重新生成(不新增用户消息,替换原助手回复)
     */
    /**
     * 一轮对话的准备结果(定位会话→装配资源→预算预检→选择执行器);
     * regen=true 表示重新生成(不新增用户消息,替换原助手回复);
     * workflowMode 非空表示本轮路由到工作流执行(P2/W10),不走 AgentRunner
     */
    private record Round(AgentSession session, boolean newSession, AgentInfo agent, AgentModel model,
                         AgentMessage userMsg, String content, List<Long> toolIds, List<Long> knowledgeIds,
                         List<KnowledgeSearchHitVo> knowledgeHits, String roundId, String budgetNotice,
                         AgentRunner.RunnerContext ctx, AgentRunner runner, boolean regen,
                         Map<String, Object> buildMetrics, String workflowMode, int ragRefCount) {
    }

    /**
     * 组装并异步记录一次模型调用用量(真实用量优先,缺失时按约4字符=1token估算)
     */
    private void recordUsage(AgentInfo agent, AgentModel model, AgentSession session,
                             String input, String output, AgentRunner.RunnerResult result) {
        try {
            com.forgeagent.agent.domain.AgentTokenUsage usage = new com.forgeagent.agent.domain.AgentTokenUsage();
            usage.setWorkspaceId(agent.getWorkspaceId());
            usage.setAgentId(agent.getId());
            usage.setSessionId(session.getId());
            usage.setRoundRequestId(java.util.UUID.randomUUID().toString().replace("-", ""));
            usage.setUserId(session.getUserId());
            usage.setSource("chat");
            usage.setModelId(model == null ? null : model.getId());
            usage.setModelName(model == null ? "" : model.getModelName());
            Integer prompt = result == null ? null : result.promptTokens();
            Integer completion = result == null ? null : result.completionTokens();
            Integer total = result == null ? null : result.totalTokens();
            if (prompt == null || completion == null || total == null) {
                prompt = Math.max(1, (input == null ? 0 : input.length()) / 4);
                completion = Math.max(1, (output == null ? 0 : output.length()) / 4);
                total = prompt + completion;
                usage.setEstimated("1");
            } else {
                usage.setEstimated("0");
            }
            usage.setPromptTokens(prompt);
            usage.setCompletionTokens(completion);
            usage.setTotalTokens(total);
            usage.setSuccess("0");
            usage.setCallTime(java.time.LocalDateTime.now());
            usageRecorder.record(usage);
            // 用量变化后失效累计缓存,下一轮预算预检重新汇总
            RedisUtils.deleteObject(ChatContextAssembler.TOKEN_SUM_CACHE_PREFIX + agent.getId());
        } catch (Exception e) {
            // 采集失败不影响会话主流程
        }
    }

    @Override
    public ChatSendVo send(ChatSendBo bo) {
        // 与流式路径一致:不走整体事务,避免 LLM 长调用持有数据库连接耗尽连接池;
        // 准备阶段逐步落库,执行失败时补偿删除本轮消息
        org.redisson.api.RLock lock = tryLockSession(bo.getSessionId());
        try {
            Round round = prepareRound(bo, resolveCallerUserId(bo));
            if (round.budgetNotice() != null) {
                return persistBudgetReply(round);
            }
            return runBlocking(round);
        } finally {
            unlockSession(lock);
        }
    }

    @Override
    public ChatSendVo sendAsUser(Long agentId, String content, Long operatorUserId) {
        // 触发器场景无登录上下文,以触发器归属人身份建会话并执行
        ChatSendBo bo = new ChatSendBo();
        bo.setAgentId(agentId);
        bo.setContent(content);
        Round round = prepareRound(bo, operatorUserId);
        if (round.budgetNotice() != null) {
            return persistBudgetReply(round);
        }
        return runBlocking(round);
    }

    /**
     * 阻塞式执行:LLM 调用在事务外,失败补偿后原样抛出交由全局异常处理返回
     */
    private ChatSendVo runBlocking(Round round) {
        String runId = round.roundId();
        long startAt = System.currentTimeMillis();
        startTrace(round, runId, "blocking");
        traceBuildStages(round, runId);
        try {
            AgentRunner.RunnerResult result = round.workflowMode() != null
                ? runWorkflowBlocking(round, runId)
                : round.runner().reply(round.ctx(), round.content());
            ChatSendVo vo = finishRound(round, result);
            finishTrace(runId, "success", startAt, result);
            return vo;
        } catch (Exception e) {
            compensate(round);
            finishTrace(runId, "failed", startAt, null);
            throw e;
        } finally {
            // P3/M1 L1:本轮工作记忆不再需要,条目自身有 TTL 兜底
            runWorkingMemory.clear(runId);
        }
    }

    /**
     * P2/W10:阻塞式工作流执行(同步聊天路径)。工作流失败不降级 ReAct(避免重复计费),
     * 失败原因作为本轮回复落库;审批暂停时提示语作为回复。
     */
    private AgentRunner.RunnerResult runWorkflowBlocking(Round round, String runId) {
        long wfStart = System.currentTimeMillis();
        WorkflowTrialRunVo vo;
        try {
            vo = workflowService.triggerRun(round.agent().getId(),
                buildWorkflowInputs(round.content()), round.session().getUserId());
        } catch (Exception e) {
            String error = streamSupport.safeMessage(e);
            runTrace.step(runId, TraceStepType.WORKFLOW, "工作流执行失败", System.currentTimeMillis() - wfStart, null,
                Map.of("error", error, "mode", round.workflowMode()));
            return new AgentRunner.RunnerResult("工作流执行失败:" + error, null, null, null, "workflow");
        }
        recordWorkflowTraceStep(round, runId, vo.getRunId(), vo, System.currentTimeMillis() - wfStart);
        return new AgentRunner.RunnerResult(extractWorkflowReply(vo), null, null, null, "workflow");
    }

    @Override
    public void streamSend(ChatSendBo bo, StreamEmitter emitter) {
        // 流式路径不走整体事务:准备阶段逐步落库,执行失败时补偿删除本轮消息
        org.redisson.api.RLock lock;
        try {
            lock = tryLockSession(bo.getSessionId());
        } catch (Exception e) {
            streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
            return;
        }
        try {
            Round round;
            try {
                round = prepareRound(bo, resolveCallerUserId(bo));
            } catch (Exception e) {
                streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
                return;
            }
            doStream(round, emitter);
        } finally {
            unlockSession(lock);
        }
    }

    @Override
    public ChatSendVo regenerate(Long sessionId) {
        org.redisson.api.RLock lock = tryLockSession(sessionId);
        try {
            Round round = prepareRegenerate(sessionId);
            return runBlocking(round);
        } finally {
            unlockSession(lock);
        }
    }

    @Override
    public void streamRegenerate(Long sessionId, StreamEmitter emitter) {
        org.redisson.api.RLock lock;
        try {
            lock = tryLockSession(sessionId);
        } catch (Exception e) {
            streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
            return;
        }
        try {
            Round round;
            try {
                round = prepareRegenerate(sessionId);
            } catch (Exception e) {
                streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
                return;
            }
            doStream(round, emitter);
        } finally {
            unlockSession(lock);
        }
    }

    /**
     * 调试会话消息上限(超出后截断最早消息)
     */
    private static final int DEBUG_SESSION_MSG_LIMIT = 20;

    @Override
    public void streamDebugSend(ChatSendBo bo, StreamEmitter emitter) {
        AgentDraftBo draft = bo.getAgentDraft();
        if (draft == null || draft.getAgentId() == null) {
            streamSupport.emitSse(emitter, "error", Map.of("message", "agentDraft.agentId 不能为空"));
            return;
        }
        // 定位/创建临时会话:debugSessionId 为空时新建 temp=1 会话,非空时校验归属
        AgentSession session;
        boolean newSession;
        if (bo.getDebugSessionId() != null) {
            try {
                session = contextAssembler.getOwned(bo.getDebugSessionId());
                if (session.getTemp() == null || session.getTemp() != 1) {
                    streamSupport.emitSse(emitter, "error", Map.of("message", "目标会话不是调试会话"));
                    return;
                }
            } catch (Exception e) {
                streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
                return;
            }
            newSession = false;
        } else {
            AgentInfo agent = agentConfigCache.getAgentById(draft.getAgentId());
            if (agent == null) {
                streamSupport.emitSse(emitter, "error", Map.of("message", "Agent 不存在"));
                return;
            }
            AgentSession created = new AgentSession();
            created.setAgentId(draft.getAgentId());
            created.setUserId(LoginHelper.getUserId());
            created.setTitle("[调试] " + ChatTexts.truncate(bo.getContent() == null ? "" : bo.getContent(), 24));
            created.setMessageCount(0);
            created.setTemp(1);
            sessionMapper.insert(created);
            session = created;
            newSession = true;
        }
        final Long sessionId = session.getId();
        org.redisson.api.RLock lock;
        try {
            lock = tryLockSession(sessionId);
        } catch (Exception e) {
            // 锁冲突需转为 SSE error 事件,此时响应已是 text/event-stream,直接抛异常前端无法解析
            streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
            return;
        }
        try {
            // 截断:调试会话最多保留 20 条消息(含即将插入的本轮),超出按最早 id 删除
            trimDebugSessionMessages(sessionId);
            Round round;
            try {
                round = buildRound(session, newSession, bo.getContent(), null, bo.getAttachmentIds(), false, draft);
            } catch (Exception e) {
                if (newSession) {
                    try {
                        sessionMapper.deleteById(sessionId);
                    } catch (Exception ignore) {
                    }
                }
                streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
                return;
            }
            doStream(round, emitter);
        } finally {
            unlockSession(lock);
        }
    }

    /**
     * 调试会话消息截断(UX-01):超过上限时按最早 id 删除,保留最近 20 条
     */
    private void trimDebugSessionMessages(Long sessionId) {
        try {
            Long count = messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId));
            // 须为即将插入的本轮 2 条预留空间,否则插入后会超出上限
            if (count == null || count + 2 <= DEBUG_SESSION_MSG_LIMIT) {
                return;
            }
            // 找出需删除的最旧 N 条
            int needDelete = (int) (count - DEBUG_SESSION_MSG_LIMIT + 2);
            List<AgentMessage> oldest = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId)
                .orderByAsc(AgentMessage::getId)
                .last("limit " + needDelete));
            if (!oldest.isEmpty()) {
                messageMapper.deleteByIds(oldest.stream().map(AgentMessage::getId).toList());
            }
        } catch (Exception e) {
            log.warn("[AgentChat] 调试会话截断失败(session={}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * 获取会话执行锁:看门狗自动续期,进程异常退出后锁会自动释放
     *
     * @return 已持有的锁;sessionId 为空(新会话)或 Redis 不可用时返回 null 降级为无锁执行
     */
    private org.redisson.api.RLock tryLockSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            org.redisson.api.RLock lock = RedisUtils.getClient().getLock(SESSION_LOCK_PREFIX + sessionId);
            if (!lock.tryLock(0, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new ServiceException("该会话正在处理中,请等待当前回复结束后再发送");
            }
            return lock;
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("获取会话执行锁被中断,请稍后重试");
        } catch (Exception e) {
            // Redis 异常时降级为无锁执行,保证对话可用性
            log.warn("[AgentChat] 会话锁获取失败,降级为无锁执行(session={}): {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 释放会话执行锁(仅当前线程持有时释放)
     */
    private void unlockSession(org.redisson.api.RLock lock) {
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[AgentChat] 会话锁释放失败: {}", e.getMessage());
        }
    }

    /**
     * 准备重新生成:定位最后一条助手回复并删除,复用其前的用户消息重建轮次;
     * 末尾即用户消息(上次生成失败/中断)时直接续写该消息
     */
    private Round prepareRegenerate(Long sessionId) {
        AgentSession session = contextAssembler.getOwned(sessionId);
        AgentMessage lastAssistant = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, sessionId)
            .eq(AgentMessage::getRole, "assistant")
            .orderByDesc(AgentMessage::getId)
            .last("limit 1"));
        AgentMessage lastUser = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, sessionId)
            .eq(AgentMessage::getRole, "user")
            .orderByDesc(AgentMessage::getId)
            .last("limit 1"));
        if (lastUser == null
            || (StringUtils.isBlank(lastUser.getContent()) && StringUtils.isBlank(lastUser.getAttachments()))) {
            throw new ServiceException("没有可重新生成的回复");
        }
        AgentMessage userMsg = lastUser;
        if (lastAssistant != null && lastAssistant.getId() > lastUser.getId()) {
            // 末尾是助手回复:删除后基于其前的用户消息重新生成
            messageMapper.deleteById(lastAssistant.getId());
        }
        // 末尾即用户消息(上次生成失败/中断):直接续写该消息
        return buildRound(session, false, userMsg.getContent(), userMsg,
            contextAssembler.parseAttachmentIds(userMsg.getAttachments()), true);
    }

    @Override
    public void feedback(Long messageId, String feedback, String comment, String tags) {
        if (StringUtils.isNotBlank(feedback) && !"like".equals(feedback) && !"dislike".equals(feedback)) {
            throw new ServiceException("非法的反馈类型,仅支持 like/dislike");
        }
        if (StringUtils.isNotBlank(comment) && comment.length() > 500) {
            throw new ServiceException("反馈备注不能超过 500 字");
        }
        if (StringUtils.isNotBlank(tags) && tags.length() > 500) {
            throw new ServiceException("反馈标签过长");
        }
        AgentMessage msg = messageMapper.selectById(messageId);
        if (msg == null) {
            throw new ServiceException("消息不存在");
        }
        AgentSession session = contextAssembler.getOwned(msg.getSessionId());
        if (!"assistant".equals(msg.getRole())) {
            throw new ServiceException("仅助手消息支持反馈");
        }
        String oldFeedback = StringUtils.defaultString(msg.getFeedback());
        String normalized = StringUtils.isBlank(feedback) ? "" : feedback;
        if (normalized.isEmpty()) {
            // 取消反馈:备注/标签/时间一并清空(updateById 忽略 null,显式 update 写入)
            messageMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentMessage>lambdaUpdate()
                .eq(AgentMessage::getId, messageId)
                .set(AgentMessage::getFeedback, "")
                .set(AgentMessage::getFeedbackComment, "")
                .set(AgentMessage::getFeedbackTags, "")
                .set(AgentMessage::getFeedbackTime, null));
        } else {
            AgentMessage update = new AgentMessage();
            update.setId(messageId);
            update.setFeedback(normalized);
            // 点赞不携带备注/标签,置空避免残留上一次点踩内容
            update.setFeedbackComment(StringUtils.defaultString(comment));
            update.setFeedbackTags(StringUtils.defaultString(tags));
            update.setFeedbackTime(new java.util.Date());
            messageMapper.updateById(update);
        }
        // 审计:反馈变更(含快照)
        try {
            AgentInfo agent = agentConfigCache.getAgentById(session.getAgentId());
            auditLogHelper.record(agent == null ? null : agent.getWorkspaceId(),
                "message.feedback", "message", String.valueOf(messageId),
                "dislike".equals(normalized) ? "点踩" : "like".equals(normalized) ? "点赞" : "取消反馈",
                Map.of("feedback", oldFeedback), normalized.isEmpty() ? Map.of()
                    : Map.of("feedback", normalized, "tags", StringUtils.defaultString(tags),
                        "comment", StringUtils.defaultString(comment)),
                "0", "INFO");
        } catch (Exception ignore) {
            // 审计失败不影响反馈主流程
        }
    }

    @Override
    public void recallMessage(Long messageId) {
        AgentMessage msg = messageMapper.selectById(messageId);
        if (msg == null) {
            throw new ServiceException("消息不存在");
        }
        AgentSession session = contextAssembler.getOwned(msg.getSessionId());
        if (!"user".equals(msg.getRole())) {
            throw new ServiceException("仅用户消息支持撤回");
        }
        // 仅允许撤回末轮:该消息之后至多存在一条助手回复,不破坏历史上下文
        Long afterCount = messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, msg.getSessionId())
            .gt(AgentMessage::getId, messageId));
        int after = afterCount == null ? 0 : afterCount.intValue();
        if (after > 1) {
            throw new ServiceException("仅支持撤回最后一轮消息");
        }
        if (after == 1) {
            messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, msg.getSessionId())
                .eq(AgentMessage::getRole, "assistant")
                .gt(AgentMessage::getId, messageId));
        }
        messageMapper.deleteById(messageId);
        // 回退会话计数,最后消息时间对齐到剩余末条(会话清空时保留原值)
        AgentMessage newLast = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, msg.getSessionId())
            .orderByDesc(AgentMessage::getId)
            .last("limit 1"));
        AgentSession update = new AgentSession();
        update.setId(session.getId());
        update.setMessageCount(Math.max(0,
            (session.getMessageCount() == null ? 0 : session.getMessageCount()) - 1 - after));
        if (newLast != null && newLast.getCreateTime() != null) {
            update.setLastMsgTime(java.sql.Timestamp.valueOf(newLast.getCreateTime()));
        }
        sessionMapper.updateById(update);
    }

    /**
     * 流式执行一轮对话:meta → delta* → done,error 事件表示执行失败
     */
    private void doStream(Round round, StreamEmitter emitter) {
        String runId = round.roundId();
        long startAt = System.currentTimeMillis();
        emitter.bindRun(runId, round.session().getId());
        startTrace(round, runId, round.regen() ? "regenerate" : "chat");
        try {
            traceBuildStages(round, runId);
            if (!round.regen()) {
                streamSupport.emitSse(emitter, "meta", Map.of("sessionId", round.session().getId(),
                    "userMessageId", round.userMsg().getId()));
            }
            if (round.budgetNotice() != null) {
                streamSupport.emitSse(emitter, "delta", Map.of("text", round.budgetNotice()));
                ChatSendVo vo = persistBudgetReply(round);
                runTrace.step(runId, TraceStepType.BUDGET, "Token 预算拦截", 0L, 0,
                    pick(round.buildMetrics(), "budgetNotice", "totalBuildMs"));
                streamSupport.emitSse(emitter, "done", vo);
                finishTrace(runId, "success", startAt, null);
                return;
            }
            // P2/W10:工作流路由 —— 异步执行 + 转发逐节点事件,不走 AgentRunner
            if (round.workflowMode() != null) {
                runWorkflowStreamForChat(round, emitter, startAt, runId);
                return;
            }
            java.util.concurrent.atomic.AtomicBoolean firstToken =
                new java.util.concurrent.atomic.AtomicBoolean(true);
            try {
                AgentRunner.RunnerResult result = round.runner().replyStream(round.ctx(), round.content(),
                    new AgentRunner.StreamListener() {
                        @Override
                        public void onDelta(String delta) {
                            if (firstToken.compareAndSet(true, false)) {
                                runTrace.firstToken(runId, System.currentTimeMillis() - startAt);
                            }
                            // P0/A3:逐片检查跨实例取消令牌,命中即向内核广播中断
                            runCancel.check(runId);
                            streamSupport.emitSse(emitter, "delta", Map.of("text", delta));
                        }

                        @Override
                        public void onTeamAgentStart(int index, int total, String agentName, String role) {
                            runTrace.step(runId, TraceStepType.TEAM, "接力开始 " + index, null, null,
                                Map.of("index", index, "total", total, "agentName", String.valueOf(agentName),
                                    "role", String.valueOf(role)));
                            streamSupport.emitSse(emitter, "team_agent_start",
                                Map.of("index", index, "total", total, "agentName", agentName, "role", role));
                        }

                        @Override
                        public void onTeamAgentDelta(int index, String delta) {
                            runCancel.check(runId);
                            streamSupport.emitSse(emitter, "team_delta", Map.of("index", index, "text", delta));
                        }

                        @Override
                        public void onTeamAgentDone(int index, String content, Integer totalTokens) {
                            runTrace.step(runId, TraceStepType.TEAM, "接力完成 " + index, null, totalTokens,
                                Map.of("index", index, "chars", content == null ? 0 : content.length()));
                            streamSupport.emitSse(emitter, "team_agent_done",
                                Map.of("index", index, "content", content,
                                    "totalTokens", totalTokens == null ? 0 : totalTokens));
                        }
                    });
                ChatSendVo vo = finishRound(round, result);
                // UX-03:done 前推 usage 事件,前端收到后立即刷新会话用量栏
                streamSupport.emitSse(emitter, "usage", Map.of("sessionId", round.session().getId(),
                    "lastRoundTokens", result == null || result.totalTokens() == null ? 0 : result.totalTokens()));
                streamSupport.emitSse(emitter, "done", vo);
                runTrace.step(runId, TraceStepType.LLM, "模型执行完成", System.currentTimeMillis() - startAt,
                    result == null ? null : result.totalTokens(),
                    Map.of("runner", result == null ? "" : String.valueOf(result.runner()),
                        "structured", result != null && result.structured(),
                        "budgetExceeded", result != null && result.budgetExceeded()));
                finishTrace(runId, "success", startAt, result);
            } catch (Exception e) {
                if (streamSupport.isClientDisconnect(e)) {
                    // 兼容旧发射器:断连不再强杀上游,仅标记终态;RunStreamEmitter 内部已保证生成继续
                    runTrace.finishRun(runId, "cancelled", System.currentTimeMillis() - startAt,
                        0, 0, 0, "client disconnected");
                    return;
                }
                compensate(round);
                runTrace.step(runId, TraceStepType.ERROR, e.getClass().getSimpleName(), null, null,
                    Map.of("message", streamSupport.safeMessage(e)));
                finishTrace(runId, "failed", startAt, null);
                streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
            }
        } finally {
            emitter.finish();
            // P3/M1 L1:本轮工作记忆不再需要(含工作流路径与异常路径)
            runWorkingMemory.clear(runId);
        }
    }

    /**
     * P2/W10:流式工作流执行 —— startRunForChat 异步提交(事件经 RunEventBuffer 推送),
     * 本线程轮询事件缓冲把 node_start/node_done/node_error 转发进聊天 SSE 流,
     * 终态后取 end 节点输出作为本轮回复。断连不影响后台执行(与 W9 语义一致)。
     */
    private void runWorkflowStreamForChat(Round round, StreamEmitter emitter, long startAt, String runId) {
        long wfStart = System.currentTimeMillis();
        Long wfRunId;
        try {
            wfRunId = workflowService.startRunForChat(round.agent().getId(),
                round.agent().getWorkflowJson(), buildWorkflowInputs(round.content()),
                round.session().getUserId());
        } catch (Exception e) {
            emitWorkflowFailureReply(round, emitter, runId, startAt, streamSupport.safeMessage(e));
            return;
        }
        streamSupport.emitSse(emitter, "wf_start",
            Map.of("wfRunId", wfRunId, "mode", round.workflowMode()));
        runTrace.step(runId, TraceStepType.WORKFLOW, "工作流执行开始", System.currentTimeMillis() - wfStart, null,
            Map.of("wfRunId", String.valueOf(wfRunId), "mode", round.workflowMode()));
        String key = "wf-" + wfRunId;
        long cursor = 0;
        long deadline = System.currentTimeMillis() + 600_000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                for (com.forgeagent.agent.service.run.RunEventBuffer.RunEvent ev
                    : workflowEventBuffer.replay(key, cursor)) {
                    cursor = ev.sequence();
                    forwardWorkflowEvent(runId, emitter, ev);
                }
                if (workflowEventBuffer.terminalOf(key) != null) {
                    break;
                }
                Thread.sleep(300);
            }
            WorkflowTrialRunVo vo = workflowService.runResultInternal(wfRunId);
            recordWorkflowTraceStep(round, runId, wfRunId, vo, System.currentTimeMillis() - wfStart);
            String reply = extractWorkflowReply(vo);
            AgentRunner.RunnerResult result = new AgentRunner.RunnerResult(
                reply, null, null, null, "workflow");
            // 工作流回复无逐字增量,终态后一次性补发 delta,聊天页无需特殊适配
            streamSupport.emitSse(emitter, "delta", Map.of("text", reply == null ? "" : reply));
            ChatSendVo chatVo = finishRound(round, result);
            streamSupport.emitSse(emitter, "usage", Map.of("sessionId", round.session().getId(),
                "lastRoundTokens", 0));
            streamSupport.emitSse(emitter, "done", chatVo);
            finishTrace(runId, "success", startAt, result);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            emitWorkflowFailureReply(round, emitter, runId, startAt, "工作流等待被中断");
        } catch (Exception e) {
            compensate(round);
            runTrace.step(runId, TraceStepType.ERROR, e.getClass().getSimpleName(), null, null,
                Map.of("message", streamSupport.safeMessage(e)));
            finishTrace(runId, "failed", startAt, null);
            streamSupport.emitSse(emitter, "error", Map.of("message", streamSupport.safeMessage(e)));
        } finally {
            emitter.finish();
        }
    }

    /** W10:把工作流节点事件按原事件名转发进聊天流,同时落 WORKFLOW 轨迹 */
    private void forwardWorkflowEvent(String runId, StreamEmitter emitter,
                                      com.forgeagent.agent.service.run.RunEventBuffer.RunEvent ev) {
        try {
            Map<String, Object> d = com.forgeagent.common.json.utils.JsonUtils.parseMap(ev.data());
            String nodeId = String.valueOf(d.getOrDefault("nodeId", "-"));
            String nodeName = String.valueOf(d.getOrDefault("nodeName", ""));
            String nodeType = String.valueOf(d.getOrDefault("nodeType", ""));
            if ("node_start".equals(ev.name())) {
                runTrace.step(runId, TraceStepType.WORKFLOW, "节点开始:" + nodeName, null, null,
                    Map.of("wfNodeId", nodeId, "wfNodeType", nodeType));
            } else if ("node_done".equals(ev.name())) {
                runTrace.step(runId, TraceStepType.WORKFLOW, "节点完成:" + nodeName, null, null,
                    Map.of("wfNodeId", nodeId, "wfNodeType", nodeType,
                        "wfStatus", String.valueOf(d.getOrDefault("status", ""))));
            } else if ("node_error".equals(ev.name())) {
                runTrace.step(runId, TraceStepType.WORKFLOW, "节点失败:" + nodeName, null, null,
                    Map.of("wfNodeId", nodeId, "error", String.valueOf(d.getOrDefault("error", ""))));
            }
            // 事件载荷原样透传给前端(体检 E1:前端消费 node_start/node_done/node_error 渲染工作流时间线)
            streamSupport.emitSse(emitter, ev.name(), d);
        } catch (Exception ignore) {
            // 转发失败不终止聊天流
        }
    }

    /** W10:工作流失败时的兜底回复(错误文本作为助手消息落库,保证会话完整) */
    private void emitWorkflowFailureReply(Round round, StreamEmitter emitter, String runId,
                                          long startAt, String error) {
        AgentRunner.RunnerResult result = new AgentRunner.RunnerResult(
            "工作流执行失败:" + error, null, null, null, "workflow");
        runTrace.step(runId, TraceStepType.WORKFLOW, "工作流提交失败", System.currentTimeMillis() - startAt, null,
            Map.of("error", error));
        ChatSendVo chatVo = finishRound(round, result);
        streamSupport.emitSse(emitter, "done", chatVo);
        finishTrace(runId, "success", startAt, result);
    }

    /** W10:工作流输入约定 {"query": 用户消息} */
    private String buildWorkflowInputs(String content) {
        return JsonUtils.toJsonString(Map.of("query", content == null ? "" : content));
    }

    /** W10:WORKFLOW 汇总轨迹(终态后统一落一条,含 wfRunId 供下钻) */
    private void recordWorkflowTraceStep(Round round, String runId, Long wfRunId,
                                         WorkflowTrialRunVo vo, long elapsedMs) {
        runTrace.step(runId, TraceStepType.WORKFLOW, "工作流执行完成", elapsedMs, null,
            Map.of("wfRunId", String.valueOf(wfRunId),
                "wfStatus", String.valueOf(vo.getStatus()),
                "wfNodes", vo.getTrace() == null ? 0 : vo.getTrace().size(),
                "mode", String.valueOf(round.workflowMode())));
    }

    /** W10:提取工作流回复 —— 优先 end 节点输出,其次轨迹末条输出/状态消息 */
    private String extractWorkflowReply(WorkflowTrialRunVo vo) {
        if (vo == null) {
            return "工作流未返回结果";
        }
        if ("pending_approval".equals(vo.getStatus())) {
            return vo.getMessage() == null ? "工作流已暂停,等待人工审批" : vo.getMessage();
        }
        if (vo.getTrace() != null) {
            for (int i = vo.getTrace().size() - 1; i >= 0; i--) {
                WorkflowTrialRunVo.NodeTrace t = vo.getTrace().get(i);
                if ("end".equals(t.getNodeType()) && t.getOutput() != null && !t.getOutput().isBlank()) {
                    return t.getOutput();
                }
            }
        }
        return vo.getMessage() == null || vo.getMessage().isBlank()
            ? "工作流执行完成,但结束节点未配置输出" : vo.getMessage();
    }

    /**
     * P2/W10:判定本轮是否路由到工作流。auto=每轮执行;manual=问题命中工具或知识意图时执行;
     * 未配置工作流或模式关闭返回 null(走 ReAct)
     */
    private String resolveWorkflowMode(AgentInfo agent, IntentRouter.Plan route) {
        if (StringUtils.isBlank(agent.getWorkflowJson())) {
            return null;
        }
        String mode = agent.getWorkflowTriggerMode();
        if ("1".equals(mode)) {
            return "auto";
        }
        if ("2".equals(mode) && (route.needTool() || route.needRag())) {
            return "manual";
        }
        return null;
    }

    private void startTrace(Round round, String runId, String runType) {
        runTrace.startRun(new RunTraceService.RunMeta(runId,
            round.agent().getWorkspaceId(), round.agent().getId(), round.session().getId(),
            round.session().getUserId(),
            round.model() == null ? null : round.model().getModelName(), runType));
    }

    private void finishTrace(String runId, String status, long startAt, AgentRunner.RunnerResult result) {
        Integer prompt = result == null ? null : result.promptTokens();
        Integer completion = result == null ? null : result.completionTokens();
        Integer total = result == null ? null : result.totalTokens();
        runTrace.finishRun(runId, status, System.currentTimeMillis() - startAt,
            prompt, completion, total, null);
    }

    /** 把 buildRound 采集的阶段耗时与命中信息落成可下钻的步骤 */
    private void traceBuildStages(Round round, String runId) {
        Map<String, Object> m = round.buildMetrics();
        if (m == null || m.isEmpty()) {
            return;
        }
        runTrace.step(runId, TraceStepType.ROUTER, "意图路由", ms(m, "routeMs"), null,
            pick(m, "routeNeedTool", "routeNeedRag", "routeDirectAnswer",
                "routeNeedsClarification", "routeConfidence", "routeAllowedTools", "routeSummary"));
        runTrace.step(runId, TraceStepType.CONTEXT, "上下文装配", ms(m, "totalBuildMs"), null,
            pick(m, "historySize", "contextChars", "toolCount", "mcpCount", "imageCount", "modelName",
                "promptTemplateId", "promptVersionId", "promptContentHash", "promptLayers",
                "contextWindowTokens", "contextOutputReserve", "contextUsedTokens", "contextBlockTokens",
                "contextTrimmed", "historyBudget", "historyTokens", "historyDropped", "historyTotal"));
        runTrace.step(runId, TraceStepType.RETRIEVAL, "知识检索", ms(m, "retrievalMs"), null,
            pick(m, "rewriteMs", "knowledgeHits", "attachmentHits", "retrievedChars"));
        runTrace.step(runId, TraceStepType.MEMORY, "长期记忆", ms(m, "memoryMs"), null,
            pick(m, "memoryEnabled"));
        runTrace.step(runId, TraceStepType.SEMANTIC, "语义与问数上下文", ms(m, "semanticMs"), null,
            pick(m, "sqlTool"));
        runTrace.step(runId, TraceStepType.BUDGET, "预算预检", ms(m, "persistMs"), null,
            pick(m, "budgetNotice"));
    }

    private static Map<String, Object> pick(Map<String, Object> src, String... keys) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (src != null) {
            for (String key : keys) {
                out.put(key, src.get(key));
            }
        }
        return out;
    }

    private static long ms(Map<String, Object> m, String key) {
        Object value = m == null ? null : m.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 准备一轮对话:定位/创建会话,资源装配与执行器选择委托给 buildRound
     */
    private Round prepareRound(ChatSendBo bo, Long operatorUserId) {
        // 1. 定位会话(必要时自动创建)
        AgentSession session;
        boolean newSession = bo.getSessionId() == null;
        if (bo.getSessionId() != null) {
            // S0 客服链路:访客无登录态,按渠道服务账号 ID 校验归属,不走 getOwned
            session = bo.getCallerUserId() != null
                ? loadCustomerServiceSession(bo.getSessionId(), bo.getCallerUserId())
                : contextAssembler.getOwned(bo.getSessionId());
        } else {
            if (bo.getAgentId() == null) {
                throw new ServiceException("会话ID与AgentID至少提供一个");
            }
            AgentSession created = new AgentSession();
            created.setAgentId(bo.getAgentId());
            created.setUserId(operatorUserId);
            created.setTitle(ChatTexts.truncate(bo.getContent(), 30));
            created.setMessageCount(0);
            sessionMapper.insert(created);
            session = created;
        }
        // S0:调用方空间经实体瞬态字段传入 buildRound,避免改多处 buildRound 签名
        session.setCallerWorkspaceId(bo.getCallerWorkspaceId());
        try {
            return buildRound(session, newSession, bo.getContent(), null, bo.getAttachmentIds(), false);
        } catch (Exception e) {
            // 无整体事务后,新会话在构建阶段失败(如供应商停用)需显式清理,避免残留空会话
            if (newSession) {
                try {
                    sessionMapper.deleteById(session.getId());
                } catch (Exception ignore) {
                    // 清理失败不覆盖原始异常
                }
            }
            throw e;
        }
    }

    /**
     * S0 客服链路:解析本轮归属用户。callerUserId 非空(客服访客)时用它,
     * 控制台路径仍回退到 LoginHelper,行为完全不变。
     */
    private Long resolveCallerUserId(ChatSendBo bo) {
        return bo != null && bo.getCallerUserId() != null
            ? bo.getCallerUserId() : LoginHelper.getUserId();
    }

    /**
     * S0 客服链路:免登录下按渠道服务账号 ID 读取并校验会话归属。
     * 不依赖 Sa-Token,也不复用 getOwned(内部取登录态)。
     */
    private AgentSession loadCustomerServiceSession(Long sessionId, Long callerUserId) {
        AgentSession found = sessionMapper.selectById(sessionId);
        if (found == null
            || !java.util.Objects.equals(found.getUserId(), callerUserId)
            || (found.getDelFlag() != null && found.getDelFlag() != 0)) {
            throw new ServiceException("会话不存在或无权访问");
        }
        return found;
    }

    /**
     * 构建一轮对话:加载 Agent 与绑定资源 → 校验图片附件 → RAG 检索 → 长期记忆注入 →
     * 用户消息落库 → 预算预检 → 选择执行器;existingUserMsg 非空时复用该用户消息不再落库(重新生成场景)
     */
    private Round buildRound(AgentSession session, boolean newSession, String content,
                             AgentMessage existingUserMsg, List<Long> attachmentIds, boolean regen) {
        return buildRound(session, newSession, content, existingUserMsg, attachmentIds, regen, null);
    }

    /**
     * 构建一轮对话:加载 Agent 与绑定资源 → 校验图片附件 → RAG 检索 → 长期记忆注入 →
     * 用户消息落库 → 预算预检 → 选择执行器;existingUserMsg 非空时复用该用户消息不再落库(重新生成场景)
     *
     * @param draft 调试草稿(UX-01):非空时覆盖 Agent 的提示词/模型/工具/知识库/MCP 绑定,不写回 agent_info
     */
    private Round buildRound(AgentSession session, boolean newSession, String content,
                             AgentMessage existingUserMsg, List<Long> attachmentIds, boolean regen,
                             AgentDraftBo draft) {
        Map<String, Object> buildMetrics = new java.util.LinkedHashMap<>();
        long buildStart = System.currentTimeMillis();
        long stageAt = buildStart;
        // 0. 预加载会话历史消息(P3/M1:由固定 20 条放宽到可配上限,实际注入量由 L0 预算决定);
        //    供 RAG 改写与内核历史注入共用,避免两次 selectList
        int preloadLimit = chatProperties.getHistoryPreloadLimit() == null
            ? 80 : Math.max(20, Math.min(200, chatProperties.getHistoryPreloadLimit()));
        List<AgentMessage> sessionRecent = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, session.getId())
            .orderByDesc(AgentMessage::getId)
            .last("limit " + preloadLimit));
        java.util.Collections.reverse(sessionRecent);

        // 2. 加载 Agent 与绑定资源(热点配置走缓存,避免每次对话 selectById)
        AgentInfo agent = agentConfigCache.getAgentById(session.getAgentId());
        if (agent == null) {
            throw new ServiceException("会话对应的 Agent 已不存在");
        }
        // 体检修复(H1):会话归属 ≠ Agent 空间归属——禁止用任意 agentId 跨空间执行他人 Agent
        // (对话、工具、工作流路由全走此处,统一在此收敛空间成员校验;触发器以归属人身份执行,归属人必然是空间成员)
        // S0 客服链路:访客无登录态,改用 ResourceAccessGuard 纯函数按渠道空间校验
        if (session.getCallerWorkspaceId() != null) {
            resourceGuard.assertReadableBy(agent.getWorkspaceId(), session.getCallerWorkspaceId());
        } else {
            permissionHelper.checkWsMember(agent.getWorkspaceId());
        }
        // UX-01 调试模式:用未保存的草稿字段覆盖 DB 配置;团队接力在调试场景禁用
        if (draft != null) {
            if (draft.getSystemPrompt() != null) {
                agent.setSystemPrompt(draft.getSystemPrompt());
            }
            if (draft.getModelId() != null) {
                agent.setModelId(draft.getModelId());
            }
            if (draft.getToolIdsStr() != null) {
                agent.setToolIdsStr(draft.getToolIdsStr());
            }
            if (draft.getKnowledgeIdsStr() != null) {
                agent.setKnowledgeIdsStr(draft.getKnowledgeIdsStr());
            }
            if (draft.getMcpIdsStr() != null) {
                agent.setMcpIdsStr(draft.getMcpIdsStr());
            }
            // 调试不支持团队接力与记忆注入,避免污染正常记忆
            agent.setTeamMode(null);
            agent.setMemoryEnabled("0");
        }
        AgentModel model = agent.getModelId() == null ? null : agentConfigCache.getModelById(agent.getModelId());
        if (model != null) {
            resourceGuard.assertReadableBy(model.getWorkspaceId(), agent.getWorkspaceId());
        }
        // FR-M6-01: 新建会话时校验模型所属供应商状态,供应商停用则拒绝新会话(存量会话不受影响)
        if (newSession && model != null && StringUtils.isNotBlank(model.getProvider())) {
            com.forgeagent.agent.domain.AgentProvider provider = providerMapper.selectOne(
                new LambdaQueryWrapper<com.forgeagent.agent.domain.AgentProvider>()
                    .eq(com.forgeagent.agent.domain.AgentProvider::getProviderCode, model.getProvider())
                    .last("limit 1"));
            if (provider != null && "1".equals(provider.getStatus())) {
                throw new ServiceException("该模型所属供应商[" + provider.getProviderName() + "]已停用,无法发起新会话");
            }
        }
        // 2.1 解析并校验本轮附件(重新生成场景宽松处理:附件已落库,查不到按无附件)
        List<AgentChatAttachment> atts = contextAssembler.resolveAttachments(attachmentIds, agent, model, regen);
        List<AgentChatAttachment> imageAtts = atts.stream().filter(a -> !"file".equals(a.getKind())).toList();
        List<AgentChatAttachment> fileAtts = atts.stream().filter(a -> "file".equals(a.getKind())).toList();
        List<AgentRunner.ImageRef> images = contextAssembler.buildImageRefs(imageAtts, regen);
        List<Long> toolIds = contextAssembler.splitIds(agent.getToolIdsStr());
        List<Long> knowledgeIds = contextAssembler.splitIds(agent.getKnowledgeIdsStr());
        List<Long> mcpIds = contextAssembler.splitIds(agent.getMcpIdsStr());
        String toolNames = toolIds.isEmpty() ? "" : toolMapper.selectByIds(toolIds).stream()
            .map(AgentTool::getName).collect(Collectors.joining("、"));
        String knowledgeNames = knowledgeIds.isEmpty() ? "" : knowledgeMapper.selectByIds(knowledgeIds).stream()
            .map(AgentKnowledge::getName).collect(Collectors.joining("、"));
        // 工具定义快照:提前加载,供统一查询改写判断是否绑定了问数数据源
        List<ToolAssembler.ToolDef> loadedTools = contextAssembler.loadToolDefs(toolIds, agent.getWorkspaceId());
        // P2/C1:先产出结构化路由计划,再按授权边界裁剪本轮工具集(未装配的工具模型无法调用)
        long routeStart = System.currentTimeMillis();
        IntentRouter.Plan route = intentRouter.plan(content, loadedTools, !knowledgeIds.isEmpty());
        List<ToolAssembler.ToolDef> toolDefs = intentRouter.filterTools(loadedTools, route);
        buildMetrics.put("routeMs", System.currentTimeMillis() - routeStart);
        buildMetrics.put("routeNeedTool", route.needTool());
        buildMetrics.put("routeNeedRag", route.needRag());
        buildMetrics.put("routeDirectAnswer", route.directAnswer());
        buildMetrics.put("routeNeedsClarification", route.needsClarification());
        buildMetrics.put("routeConfidence", route.confidence());
        buildMetrics.put("routeAllowedTools", route.allowedTools());
        buildMetrics.put("routeSummary", route.summary());
        // P2/W10:对话路由到工作流 —— auto 每轮执行;manual 仅当问题命中工具/知识意图时执行
        String workflowMode = resolveWorkflowMode(agent, route);
        buildMetrics.put("workflowMode", workflowMode == null ? "off" : workflowMode);
        // 多轮对话统一查询改写:RAG 检索与问数语义路由共用(补全指代/省略,提升召回与语义命中);
        // 仅绑定了知识库或问数数据源时才触发,避免普通会话多一次 LLM 调用
        boolean hasSqlTool = toolDefs.stream().anyMatch(d -> "sql".equals(d.toolType()) && d.datasourceId() != null);
        String rewrittenQuery = retrievalSupport.rewriteUserQuery(session, content, model, sessionRecent,
            !knowledgeIds.isEmpty() || hasSqlTool);
        buildMetrics.put("rewriteMs", System.currentTimeMillis() - stageAt);
        stageAt = System.currentTimeMillis();
        // RAG: 用改写后的问题对绑定知识库混合检索(未绑知识库时保持原文,改写结果仅供问数路由使用)
        String retrievalQuery = knowledgeIds.isEmpty() ? content : rewrittenQuery;
        ChatRetrievalSupport.RetrievalResult retrieval = retrievalSupport.retrieveKnowledge(knowledgeIds, retrievalQuery,
            new ChatRetrievalSupport.RetrievalTrace(agent.getId(), session.getId(), session.getUserId(), content));
        // 会话文件:小文件全文注入,大文件只注入摘要+按问题召回的片段,并受附件预算约束
        ChatRetrievalSupport.AttachmentRetrieval attachmentRetrieval = retrievalSupport.retrieveAttachments(fileAtts, retrievalQuery, content);
        buildMetrics.put("retrievalMs", System.currentTimeMillis() - stageAt);
        buildMetrics.put("knowledgeHits", retrieval.hits().size());
        buildMetrics.put("attachmentHits", attachmentRetrieval.hits() == null ? 0 : attachmentRetrieval.hits().size());
        stageAt = System.currentTimeMillis();
        List<KnowledgeSearchHitVo> knowledgeHits = new java.util.ArrayList<>(retrieval.hits());
        knowledgeHits.addAll(attachmentRetrieval.hits());
        // P3/M1:L0 统一预算分配前,检索/附件各自成块,由分配器决定注入多少
        // R4-1:结构化资料块(JSON 引用列表 + 合法编号清单),ragRefCount 供 R4-2 引用校验对账
        boolean strictFallback = !knowledgeIds.isEmpty()
            && knowledgeMapper.selectByIds(knowledgeIds).stream()
            .allMatch(k -> Integer.valueOf(0).equals(k.getFallbackAnswerEnabled()));
        ChatRetrievalSupport.ReferenceBlock refBlock = knowledgeIds.isEmpty()
            ? new ChatRetrievalSupport.ReferenceBlock("", -1)
            : retrievalSupport.buildReferenceBlock(retrieval.hits(), retrieval.contextByChunk(), strictFallback);
        String ragBlock = refBlock.text();
        int ragRefCount = refBlock.citationCount();
        String attachmentBlock = StringUtils.defaultString(attachmentRetrieval.text());
        // 空召回:绑定了知识库但零命中时,资料块内已含"暂无相关资料"与兜底/拒答指令(R4-3,
        // 按 fallback_answer_enabled 分严格/宽松两版);同时异步落零命中日志供运营发现知识缺口
        if (!knowledgeIds.isEmpty() && retrieval.hits().isEmpty()) {
            knowledgeService.recordZeroHit(agent.getId(), session.getId(), session.getUserId(),
                content, retrievalQuery, knowledgeIds);
        }
        // 长期记忆注入:开启记忆的 Agent 取最近记忆追加到系统提示词(P3/M1:改为独立块,交给 L0 分配器)
        String basePrompt = agent.getSystemPrompt();
        // P1/B2:Prompt 模板编译(MANUAL 不介入;调试草稿跳过模板,避免与 draft 覆盖冲突)
        if (draft == null && agent.getPromptTemplateId() != null) {
            PromptTemplateService.Compiled compiled = promptTemplateService.compileForAgent(agent);
            if (compiled != null) {
                basePrompt = compiled.content();
                buildMetrics.put("promptTemplateId", compiled.templateId());
                buildMetrics.put("promptVersionId", compiled.versionId());
                buildMetrics.put("promptContentHash", compiled.contentHash());
                buildMetrics.put("promptLayers", compiled.layers());
            }
        }
        // P2/C1:缺实体只注入澄清提示,不阻断对话,由模型自行确认后再决定是否调用工具
        if (route.needsClarification()) {
            basePrompt = (StringUtils.isBlank(basePrompt) ? "" : basePrompt + "\n\n")
                + "本轮问题可能缺少明确的业务编号或查询对象,请先向用户确认关键信息,不要凭推测调用工具。";
        }
        long memoryStart = System.currentTimeMillis();
        String memoryBlock = "";
        if ("1".equals(agent.getMemoryEnabled())) {
            // P3/M2':把本轮问题传入,开启向量时用于语义召回;纯附件消息无文本则自动回退评分召回
            memoryBlock = StringUtils.defaultString(
                memoryService.buildMemoryBlock(agent.getId(), session.getUserId(), content));
        }
        buildMetrics.put("memoryMs", System.currentTimeMillis() - memoryStart);
        buildMetrics.put("memoryEnabled", "1".equals(agent.getMemoryEnabled()));
        stageAt = System.currentTimeMillis();
        // MCP 定义快照:经审批门装配进 AgentScope 内核 Toolkit
        List<ToolAssembler.McpDef> mcpDefs = contextAssembler.loadMcpDefs(mcpIds);
        String roundId = java.util.UUID.randomUUID().toString();
        // 问数:轮级 SQL 执行明细收集器(reactor 线程写入)+ 数据源语义上下文注入
        List<Map<String, Object>> sqlCollector = new java.util.concurrent.CopyOnWriteArrayList<>();
        String sqlSchemaBlock = semanticContextBuilder.build(toolDefs, hasSqlTool ? rewrittenQuery : content,
            agent.getWorkspaceId());
        buildMetrics.put("semanticMs", System.currentTimeMillis() - stageAt);
        buildMetrics.put("toolCount", toolDefs.size());
        buildMetrics.put("mcpCount", mcpDefs == null ? 0 : mcpDefs.size());
        buildMetrics.put("imageCount", images.size());
        buildMetrics.put("sqlTool", hasSqlTool);
        stageAt = System.currentTimeMillis();

        // 纯附件消息:只为送模文本补占位提示,落库 content 保持原文
        String modelContent = content;
        if (StringUtils.isBlank(content)) {
            if (!images.isEmpty()) {
                modelContent = "请分析我发送的 " + images.size() + " 张图片";
            } else if (!fileAtts.isEmpty()) {
                modelContent = "请阅读并分析我上传的文件：" + fileAtts.stream()
                    .map(AgentChatAttachment::getOriginalName).collect(Collectors.joining("、"));
            }
        }

        // 3. 落库用户消息(重新生成时复用原用户消息,不再插入)
        AgentMessage userMsg;
        if (existingUserMsg != null) {
            userMsg = existingUserMsg;
        } else {
            userMsg = contextAssembler.buildMessage(session.getId(), "user", content, "");
            if (!atts.isEmpty()) {
                // 图片附件摘要(仅落库展示,不含原图 base64)
                userMsg.setAttachments(contextAssembler.buildAttachmentsJson(atts));
            }
            messageMapper.insert(userMsg);
            if (!atts.isEmpty()) {
                try {
                    // 回填附件的会话与消息归属,失败不影响发送
                    attachmentService.bindMessages(atts, session.getId(), userMsg.getId());
                } catch (Exception ignore) {
                    // 回填失败不影响主流程
                }
            }
        }

        // 3.1 Token 预算预检(FR: Agent 级累计预算):历史用量已达上限时不再发起 LLM 调用
        Long tokenBudget = agent.getTokenBudget();
        Long usedTokens = null;
        String budgetNotice = null;
        if (tokenBudget != null && tokenBudget > 0) {
            usedTokens = contextAssembler.sumAgentTokens(agent.getId());
            if (usedTokens >= tokenBudget) {
                budgetNotice = com.forgeagent.agent.service.runner.TokenBudgetHook.exceedMessage(usedTokens, tokenBudget);
            }
        }

        // 3.2 P3/M1 L0:统一上下文预算分配(摘要 → 长期记忆 → 问数语义 → RAG → 附件 → 历史)
        int windowTokens = contextBudgetAllocator.defaultWindowTokens();
        if (tokenBudget != null && tokenBudget > 0) {
            long remain = tokenBudget - (usedTokens == null ? 0L : usedTokens);
            windowTokens = (int) Math.min(windowTokens, Math.max(1000L, remain));
        }
        int outputReserve = model != null && model.getMaxTokens() != null && model.getMaxTokens() > 0
            ? model.getMaxTokens() : contextBudgetAllocator.defaultOutputReserve();
        String summaryBlock = sessionSummaryService.summaryBlock(session);
        long summaryUpto = sessionSummaryService.upto(session);
        ContextBudgetAllocator.Allocation allocation = contextBudgetAllocator.allocate(
            new ContextBudgetAllocator.Spec(windowTokens, outputReserve, basePrompt, summaryBlock,
                memoryBlock, sqlSchemaBlock, ragBlock, attachmentBlock, modelContent,
                AgentRunner.timeZonePrompt()));
        String systemPrompt = allocation.systemPrompt();
        String retrievedContext = allocation.retrievedContext();
        int maxHistoryTurns = chatProperties.getHistoryMaxTurns() == null
            ? 40 : chatProperties.getHistoryMaxTurns();
        ChatContextAssembler.HistoryResult history = contextAssembler.loadHistory(
            sessionRecent, userMsg.getId(), summaryUpto, allocation.historyBudget(), maxHistoryTurns);
        buildMetrics.put("historySize", history.turns().size());
        buildMetrics.put("historyTokens", history.usedTokens());
        buildMetrics.put("historyDropped", history.droppedTurns());
        buildMetrics.put("historyTotal", history.totalTurns());
        buildMetrics.put("contextChars", systemPrompt.length());
        buildMetrics.put("retrievedChars", retrievedContext.length());
        buildMetrics.put("contextWindowTokens", allocation.windowTokens());
        buildMetrics.put("contextOutputReserve", outputReserve);
        buildMetrics.put("contextUsedTokens", allocation.usedTokens());
        buildMetrics.put("contextBlockTokens", allocation.blockTokens());
        buildMetrics.put("contextTrimmed", allocation.trimmed());
        buildMetrics.put("historyBudget", allocation.historyBudget());

        // 4. 选择执行器(模型接入信息齐备走 AgentScope 内核,否则回退 Mock)
        List<AgentRunner.RunnerContext.TeamMember> teamMembers = contextAssembler.loadTeamMembers(agent);
        AgentRunner.RunnerContext ctx = new AgentRunner.RunnerContext(
            agent.getName(), model == null ? null : model.getName(),
            toolNames, knowledgeNames, retrievedContext, systemPrompt,
            history.turns(),
            model == null ? null : model.getBaseUrl(), model == null ? null : model.getApiKey(),
            model == null ? null : model.getModelName(),
            model == null ? null : (model.getTemperature() == null ? null : model.getTemperature().doubleValue()),
            model == null ? null : model.getMaxTokens(),
            session.getId(), agent.getId(), roundId, toolDefs, mcpDefs, tokenBudget, usedTokens,
            agent.getOutputSchema(), teamMembers, agent.getTeamMode(), images,
            retrievalSupport.buildAttachmentRefs(fileAtts), sqlCollector);
        AgentRunner selected = agentRunners.stream()
            .filter(r -> r.supports(ctx))
            .findFirst()
            .orElseThrow(() -> new ServiceException("无可用执行器"));
        buildMetrics.put("persistMs", System.currentTimeMillis() - stageAt);
        buildMetrics.put("totalBuildMs", System.currentTimeMillis() - buildStart);
        buildMetrics.put("budgetNotice", budgetNotice != null);
        buildMetrics.put("modelName", model == null ? null : model.getModelName());
        return new Round(session, newSession, agent, model, userMsg, modelContent,
            toolIds, knowledgeIds, knowledgeHits, roundId, budgetNotice, ctx, selected, regen, buildMetrics,
            workflowMode, ragRefCount);
    }

    /**
     * 收尾一轮对话:落库助手回复 → 采集用量 → 更新会话统计 → 异步提取长期记忆;
     * 重新生成时替换原助手回复(消息计数不变),命中知识库时写入引用溯源
     */
    private ChatSendVo finishRound(Round round, AgentRunner.RunnerResult result) {
        String reply = result.content();
        // R4-2 引用校验(硬门禁):绑定了知识库的回合,done/落库前校验 [n] 编号,
        // 越界抛异常 → 非流式原样返回错误、流式经 catch 发 error 终态(不发 done);
        // Mock 执行回显用户输入,不作校验
        if (!"mock".equals(result.runner())
            && !Boolean.FALSE.equals(knowledgeProperties.getCitationValidateEnabled())) {
            AnswerCitationValidator.validate(reply, round.ragRefCount());
        }
        String extJson = "{\"runner\":\"" + result.runner() + "\",\"toolCount\":" + round.toolIds().size()
            + ",\"knowledgeCount\":" + round.knowledgeIds().size()
            + ",\"knowledgeHits\":" + round.knowledgeHits().size()
            + ",\"runId\":\"" + round.roundId() + "\""
            + (result.budgetExceeded() ? ",\"budgetExceeded\":true" : "")
            + (result.structured() ? ",\"structured\":true" : "")
            + contextAssembler.teamTraceJson(result) + "}";
        // 问数:SQL 执行明细随助手消息落库(前端渲染表格/图表),并异步写入审计
        List<Map<String, Object>> sqlResults = round.ctx().sqlCollector();
        if (sqlResults != null && !sqlResults.isEmpty()) {
            extJson = extJson.substring(0, extJson.length() - 1)
                + ",\"sqlResults\":" + JsonUtils.toJsonString(sqlResults) + "}";
            contextAssembler.recordSqlAudit(round.agent(), sqlResults);
        }
        AgentMessage assistantMsg = contextAssembler.buildMessage(round.session().getId(), "assistant", reply, extJson);
        assistantMsg.setCitations(contextAssembler.buildCitationsJson(round.knowledgeHits()));
        messageMapper.insert(assistantMsg);

        // Token 用量采集(FR-M7-01): 仅真实 LLM 调用采集用量,Mock 执行无真实消耗不记录
        if (!"mock".equals(result.runner())) {
            recordUsage(round.agent(), round.model(), round.session(), round.content(), reply, result);
        }

        // 更新会话统计;首轮对话结束后异步用模型生成凝练标题
        boolean firstRound = updateSessionStats(round.session(), round.content(), round.regen());

        // 长期记忆提取:开启记忆的 Agent 在对话后异步沉淀新记忆(静默失败)
        if (!round.regen() && "1".equals(round.agent().getMemoryEnabled())) {
            memoryService.extractAsync(round.agent().getId(), round.agent().getWorkspaceId(),
                round.session().getUserId(), round.session().getId(), round.content(), reply);
        }
        // P3/M1 L2:滚动摘要异步检查(调试临时会话跳过,重新生成不重复推进水位)
        if (!round.regen() && (round.session().getTemp() == null || round.session().getTemp() != 1)) {
            sessionSummaryService.schedule(round.session().getId(), round.agent().getId());
        }
        if (firstRound) {
            generateTitleAsync(round.session().getId(), round.agent().getId(), round.content(), reply);
        }

        ChatSendVo vo = new ChatSendVo();
        vo.setSessionId(round.session().getId());
        vo.setUserMessage(MapstructUtils.convert(round.userMsg(), AgentMessageVo.class));
        vo.setAssistantMessage(MapstructUtils.convert(assistantMsg, AgentMessageVo.class));
        return vo;
    }

    /**
     * 预算超限收尾:提示语直接作为助手回复落库
     */
    private ChatSendVo persistBudgetReply(Round round) {
        String notice = round.budgetNotice();
        AgentMessage budgetMsg = contextAssembler.buildMessage(round.session().getId(), "assistant", notice,
            "{\"runner\":\"agentscope-react\",\"budgetExceeded\":true}");
        messageMapper.insert(budgetMsg);
        updateSessionStats(round.session(), round.content(), false);
        ChatSendVo budgetVo = new ChatSendVo();
        budgetVo.setSessionId(round.session().getId());
        budgetVo.setUserMessage(MapstructUtils.convert(round.userMsg(), AgentMessageVo.class));
        budgetVo.setAssistantMessage(MapstructUtils.convert(budgetMsg, AgentMessageVo.class));
        return budgetVo;
    }

    /**
     * 流式执行失败的补偿:重新生成仅丢弃已删除的原回复,不再回删用户消息
     */
    private void compensate(Round round) {
        if (round.regen()) {
            return;
        }
        try {
            messageMapper.deleteById(round.userMsg().getId());
            if (round.newSession() && messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, round.session().getId())) == 0) {
                sessionMapper.deleteById(round.session().getId());
            }
        } catch (Exception ignore) {
            // 补偿失败不覆盖原始异常
        }
    }

    /**
     * 更新会话统计(消息数+2、最后消息时间、标题补齐);
     * 重新生成仅刷新最后消息时间,不改变消息计数;
     *
     * @return 是否为该会话的首轮对话(用于触发标题自动生成)
     */
    private boolean updateSessionStats(AgentSession session, String content, boolean regen) {
        AgentSession update = new AgentSession();
        update.setId(session.getId());
        update.setLastMsgTime(new Date());
        if (regen) {
            sessionMapper.updateById(update);
            return false;
        }
        boolean firstRound = session.getMessageCount() == null || session.getMessageCount() <= 0;
        update.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 2);
        if (StringUtils.isBlank(session.getTitle())) {
            update.setTitle(ChatTexts.truncate(content, 30));
        }
        sessionMapper.updateById(update);
        return firstRound;
    }

    /**
     * 标题自动生成:首轮对话后异步调用 Agent 绑定模型,按对话内容生成凝练标题
     * (失败静默,保留截断式标题)
     */
    private void generateTitleAsync(Long sessionId, Long agentId, String userContent, String assistantContent) {
        TITLE_WORKER.execute(() -> {
            try {
                AgentInfo agent = agentConfigCache.getAgentById(agentId);
                if (agent == null || agent.getModelId() == null) {
                    return;
                }
                AgentModel model = agentConfigCache.getModelById(agent.getModelId());
                if (model != null) {
                    resourceGuard.assertReadableBy(model.getWorkspaceId(), agent.getWorkspaceId());
                }
                if (model == null || StringUtils.isBlank(model.getBaseUrl())
                    || StringUtils.isBlank(model.getApiKey()) || StringUtils.isBlank(model.getModelName())) {
                    return;
                }
                io.agentscope.core.message.Msg reply = UtilityAgents.build("title-generator", TITLE_SYS_PROMPT, model)
                    .call(io.agentscope.core.message.Msg.builder()
                        .name("user")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .textContent("用户: " + ChatTexts.truncate(userContent, 200)
                            + "\n助手: " + ChatTexts.truncate(assistantContent, 300))
                        .build())
                    .block(java.time.Duration.ofSeconds(60));
                if (reply == null || StringUtils.isBlank(reply.getTextContent())) {
                    return;
                }
                String title = reply.getTextContent().trim()
                    .replaceAll("[\\r\\n]", " ")
                    .replaceAll("^[\"'“”《【]+|[\"'“”。.】》]+$", "")
                    .trim();
                if (StringUtils.isBlank(title)) {
                    return;
                }
                AgentSession update = new AgentSession();
                update.setId(sessionId);
                update.setTitle(ChatTexts.truncate(title, 30));
                sessionMapper.updateById(update);
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .info("[AgentChat] 会话 {} 标题已自动生成为: {}", sessionId, title);
            } catch (Exception e) {
                // 标题生成失败静默,保留截断式标题
            }
        });
    }

}
