package com.forgeagent.agent.service.runner;

import com.forgeagent.agentcore.service.AgentScopeModelFactory;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.config.AgentRuntimeProperties;
import com.forgeagent.agent.service.AgentRunner;
import com.forgeagent.agent.service.AgentRunner.RunnerContext.ChatTurn;
import com.forgeagent.agent.service.AgentRunner.RunnerContext.TeamMember;
import com.forgeagent.agent.service.runner.ToolAssembler.Assembly;
import com.forgeagent.agent.service.runner.ApprovalGateTool.ApprovalContext;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope ReAct 内核执行器
 *
 * <p>基于 io.agentscope:agentscope 2.0.4 的 ReActAgent 执行一轮对话：
 * 模型卡按供应商自动路由(DashScope 原生 / OpenAI 兼容协议)，注入系统提示词
 * (含知识库检索上下文)与会话历史，返回真实 Token 用量。绑定的平台工具与
 * MCP 服务器工具经 {@link ToolAssembler} 装配进内核 Toolkit(含审批门)。</p>
 *
 * <p>2.x 适配说明：内核 Builder 不再接受外部 Memory，会话历史改为随输入消息列表
 * 注入(内核 addToContext 等价落库)；{@code Pipelines} 已移除，sequential 团队
 * 改为本执行器内的顺序接力编排。</p>
 *
 * <p>第四步扩展：
 * 1) 结构化输出——配置 outputSchema 时经 {@code AgentBase.call(msgs, JsonNode)}
 * 约束回复为符合 Schema 的 JSON，结果取自 metadata._structured_output；
 * 2) 团队协作——配置 sequential 团队时顺序接力：成员 Agent(仅模型+提示词)先行
 * 处理，上一棒输出作为下一棒输入，主 Agent 最后综合，各棒输出记入 teamTrace。</p>
 *
 * <p>Agent 与 Toolkit 均为有状态对象，不可并发复用，因此每次请求构建独立实例。
 * 执行中的主 Agent 按会话注册到 RUNNING 表，供轮级中断定位(团队模式广播到全部成员)。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@Order(100)
public class AgentScopeRunner implements AgentRunner {

    /**
     * 单轮对话总超时:ReAct 循环可能包含多次模型推理与多次工具调用
     */
    private final Duration callTimeout;

    /**
     * ReAct 最大推理-执行迭代数(由 agent.runtime.scope-max-iters 配置)
     */
    private final int maxIters;

    /**
     * 结构化输出结果元数据键(SDK StructuredOutputCapableAgent 约定)
     */
    private static final String KEY_STRUCTURED_OUTPUT = "_structured_output";

    /**
     * 结构化输出聚合用量元数据键(SDK StructuredOutputHook 约定)
     */
    private static final String KEY_CHAT_USAGE = "_chat_usage";

    /**
     * Jackson 2 复用 AgentScope 传递的同版本 ObjectMapper(Schema 解析与结构化结果序列化)
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper FasterxmlMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 执行中的会话 → 内核 Agent(轮级中断定位)
     */
    private final Map<Long, ReActAgent> running = new ConcurrentHashMap<>();

    /**
     * 团队协作时本轮全部 Agent(含主 Agent),用于中断广播
     */
    private final Map<Long, List<ReActAgent>> teamAgents = new ConcurrentHashMap<>();

    /**
     * 团队并行分支有界线程池(P2 / C2):队列满时由调用线程兜底执行,不丢弃成员任务
     */
    private static final java.util.concurrent.ExecutorService TEAM_POOL =
        new java.util.concurrent.ThreadPoolExecutor(2, 8, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "agent-team-parallel");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    private final ToolAssembler toolAssembler;

    public AgentScopeRunner(ToolAssembler toolAssembler, AgentRuntimeProperties runtimeProperties) {
        this.toolAssembler = toolAssembler;
        this.callTimeout = Duration.ofSeconds(runtimeProperties.getScopeCallTimeoutSeconds());
        int configured = runtimeProperties.getScopeMaxIters();
        this.maxIters = configured > 0 && configured <= 50 ? configured : 10;
    }

    @Override
    public boolean supports(RunnerContext ctx) {
        return ctx.llmReady();
    }

    @Override
    public RunnerResult reply(RunnerContext ctx, String content) {
        if (ctx.isTeam()) {
            return "parallel".equals(ctx.teamModeOrDefault())
                ? replyTeamParallel(ctx, content) : replyTeam(ctx, content);
        }
        return replySingle(ctx, content);
    }

    @Override
    public RunnerResult replyStream(RunnerContext ctx, String content, StreamListener listener) {
        if (ctx.isTeam()) {
            return "parallel".equals(ctx.teamModeOrDefault())
                ? replyTeamParallelStream(ctx, content, listener)
                : replyTeamStream(ctx, content, listener);
        }
        if (ctx.isStructured()) {
            return AgentRunner.super.replyStream(ctx, content, listener);
        }
        return replySingleStream(ctx, content, listener);
    }

    /**
     * 单 Agent 流式执行:订阅内核 stream() 事件流,REASONING 增量片段实时回调,
     * AGENT_RESULT 作为最终回复;工具审批门与预算钩子在事件循环内照常生效。
     */
    private RunnerResult replySingleStream(RunnerContext ctx, String content, StreamListener listener) {
        Assembly assembly = toolAssembler.build(ctx.toolDefs(), ctx.mcpDefs(),
            new ApprovalContext(ctx.sessionId(), ctx.agentId(), ctx.roundId()), ctx.sqlCollector(), ctx.attachments());
        ReActAgent agent;
        TokenBudgetHook budgetHook = null;
        try {
            BuildResult built = buildAgent(ctx, assembly);
            agent = built.agent();
            budgetHook = built.budgetHook();
        } catch (Exception e) {
            assembly.close();
            throw new ServiceException("AgentScope 构建失败: " + rootMessage(e));
        }
        if (ctx.sessionId() != null) {
            running.put(ctx.sessionId(), agent);
        }
        try {
            StringBuilder emitted = new StringBuilder();
            AtomicReference<Msg> finalRef = new AtomicReference<>();
            agent.stream(withHistory(ctx, buildUserMsg(content, ctx.images())),
                    StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
                        .incremental(true)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(false)
                        .build(),
                    RuntimeContext.empty())
                .doOnNext(event -> {
                    if (event.getType() == EventType.AGENT_RESULT) {
                        if (event.getMessage() != null) {
                            finalRef.set(event.getMessage());
                        }
                        return;
                    }
                    Msg msg = event.getMessage();
                    if (msg == null || StringUtils.isBlank(msg.getTextContent())) {
                        return;
                    }
                    String text = msg.getTextContent();
                    // 兼容增量/累计两种事件语义:能接上前缀的取差值,否则整段视为增量
                    String delta;
                    if (text.length() > emitted.length() && text.startsWith(emitted.toString())) {
                        delta = text.substring(emitted.length());
                    } else {
                        delta = text;
                    }
                    if (StringUtils.isNotBlank(delta)) {
                        emitted.append(delta);
                        listener.onDelta(delta);
                    }
                })
                .blockLast(callTimeout);
            Msg reply = finalRef.get();
            if (reply == null || StringUtils.isBlank(resolveReplyText(reply, false))) {
                throw new ServiceException("AgentScope 未返回有效回复");
            }
            ChatUsage usage = resolveUsage(reply);
            return new RunnerResult(resolveReplyText(reply, false),
                usage == null ? null : usage.getInputTokens(),
                usage == null ? null : usage.getOutputTokens(),
                usage == null ? null : usage.getTotalTokens(),
                "agentscope-react",
                budgetHook != null && budgetHook.isExceeded(),
                false, List.of());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("AgentScope 执行失败: " + rootMessage(e));
        } finally {
            if (ctx.sessionId() != null) {
                running.remove(ctx.sessionId(), agent);
            }
            assembly.close();
        }
    }

    /**
     * 单 Agent 执行(兼容结构化输出)
     */
    private RunnerResult replySingle(RunnerContext ctx, String content) {
        Assembly assembly = toolAssembler.build(ctx.toolDefs(), ctx.mcpDefs(),
            new ApprovalContext(ctx.sessionId(), ctx.agentId(), ctx.roundId()), ctx.sqlCollector(), ctx.attachments());
        ReActAgent agent;
        TokenBudgetHook budgetHook = null;
        try {
            BuildResult built = buildAgent(ctx, assembly);
            agent = built.agent();
            budgetHook = built.budgetHook();
        } catch (Exception e) {
            assembly.close();
            throw new ServiceException("AgentScope 构建失败: " + rootMessage(e));
        }
        if (ctx.sessionId() != null) {
            running.put(ctx.sessionId(), agent);
        }
        Msg userMsg = buildUserMsg(content, ctx.images());
        try {
            Msg reply;
            if (ctx.isStructured()) {
                reply = agent.call(withHistory(ctx, userMsg), parseSchema(ctx.outputSchema())).block(callTimeout);
            } else {
                reply = agent.call(withHistory(ctx, userMsg)).block(callTimeout);
            }
            if (reply == null || StringUtils.isBlank(resolveReplyText(reply, ctx.isStructured()))) {
                throw new ServiceException("AgentScope 未返回有效回复");
            }
            ChatUsage usage = resolveUsage(reply);
            return new RunnerResult(resolveReplyText(reply, ctx.isStructured()),
                usage == null ? null : usage.getInputTokens(),
                usage == null ? null : usage.getOutputTokens(),
                usage == null ? null : usage.getTotalTokens(),
                "agentscope-react",
                budgetHook != null && budgetHook.isExceeded(),
                ctx.isStructured(), List.of());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("AgentScope 执行失败: " + rootMessage(e));
        } finally {
            if (ctx.sessionId() != null) {
                running.remove(ctx.sessionId(), agent);
            }
            assembly.close();
        }
    }

    /**
     * 团队协作执行(sequential 顺序接力):成员先行处理,主 Agent 最后综合
     *
     * <p>2.x 起内核已移除 {@code Pipelines}，由本执行器顺序接力编排：首棒接收
     * 会话历史+用户消息，后续各棒接收会话历史+上一棒输出，返回最后一棒(主 Agent)
     * 输出。各棒输出与用量直接取各 Agent 的返回消息。团队成员不带工具;
     * 轮级中断仅作用于主 Agent。</p>
     */
    private RunnerResult replyTeam(RunnerContext ctx, String content) {
        Assembly assembly = toolAssembler.build(ctx.toolDefs(), ctx.mcpDefs(),
            new ApprovalContext(ctx.sessionId(), ctx.agentId(), ctx.roundId()), ctx.sqlCollector(), ctx.attachments());
        List<ReActAgent> agents = new ArrayList<>();
        ReActAgent registered = null;
        try {
            for (TeamMember member : ctx.teamMembers()) {
                try {
                    agents.add(buildMemberAgent(ctx, member, assembly));
                } catch (Exception e) {
                    throw new ServiceException("协作成员[" + member.agentName() + "]构建失败: " + rootMessage(e));
                }
            }
            BuildResult leaderBuilt;
            try {
                leaderBuilt = buildAgent(ctx, assembly);
            } catch (Exception e) {
                throw new ServiceException("AgentScope 构建失败: " + rootMessage(e));
            }
            ReActAgent leader = leaderBuilt.agent();
            agents.add(leader);
            if (ctx.sessionId() != null) {
                running.put(ctx.sessionId(), leader);
                teamAgents.put(ctx.sessionId(), List.copyOf(agents));
                registered = leader;
            }
            // 顺序接力:首棒输入=历史+用户消息,后续各棒输入=历史+此前所有棒输出(主 Agent 可综合全部成员产出)
            List<Msg> history = historyMsgs(ctx);
            List<Msg> input = new ArrayList<>(history);
            input.add(buildUserMsg(content, ctx.images()));
            List<Msg> outputs = new ArrayList<>();
            Msg finalMsg = null;
            try {
                for (ReActAgent a : agents) {
                    finalMsg = a.call(input).block(callTimeout);
                    if (finalMsg == null) {
                        throw new ServiceException("AgentScope 团队执行未返回有效回复");
                    }
                    outputs.add(finalMsg);
                    // 下一棒输入=历史+此前所有棒输出
                    input = new ArrayList<>(history);
                    input.addAll(outputs);
                }
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                throw new ServiceException("AgentScope 团队执行失败: " + rootMessage(e));
            }
            // 各棒轨迹与用量聚合:每个 Agent 的返回消息即为本棒回复
            List<com.forgeagent.agent.service.AgentRunner.RunnerResult.TeamTraceLine> trace = new ArrayList<>();
            int prompt = 0;
            int completion = 0;
            int total = 0;
            boolean usagePresent = false;
            for (int i = 0; i < agents.size(); i++) {
                ReActAgent a = agents.get(i);
                boolean isLeader = i == agents.size() - 1;
                Msg own = outputs.size() > i ? outputs.get(i) : null;
                String text = own == null ? null : own.getTextContent();
                trace.add(new com.forgeagent.agent.service.AgentRunner.RunnerResult.TeamTraceLine(
                    isLeader ? null : ctx.teamMembers().get(i).agentId(),
                    a.getName(), isLeader ? "leader" : "member",
                    StringUtils.isBlank(text) ? "(无输出)" : text,
                    own == null || own.getChatUsage() == null ? null : own.getChatUsage().getTotalTokens()));
                ChatUsage u = own == null ? null : own.getChatUsage();
                if (u != null) {
                    usagePresent = true;
                    prompt += u.getInputTokens();
                    completion += u.getOutputTokens();
                    total += u.getTotalTokens();
                }
            }
            String finalText = StringUtils.isBlank(finalMsg.getTextContent())
                ? trace.get(trace.size() - 1).content() : finalMsg.getTextContent();
            return new RunnerResult(finalText,
                usagePresent ? prompt : null, usagePresent ? completion : null, usagePresent ? total : null,
                "agentscope-react", leaderBuilt.budgetHook() != null && leaderBuilt.budgetHook().isExceeded(),
                false, trace);
        } finally {
            if (registered != null) {
                running.remove(ctx.sessionId(), registered);
                teamAgents.remove(ctx.sessionId());
            }
            assembly.close();
        }
    }

    /**
     * 团队协作流式执行(sequential 顺序接力):逐棒流式输出,每棒开始/增量/完成均经 listener 回调。
     *
     * <p>与 {@link #replyTeam} 编排一致,但每棒改用 {@code agent.stream()} 订阅事件流:
     * 棒开始时回调 {@code onTeamAgentStart},增量文本回调 {@code onTeamAgentDelta},
     * 棒完成时回调 {@code onTeamAgentDone};最终返回的 RunnerResult 与 replyTeam 完全一致
     * (teamTrace 聚合各棒输出与用量),供服务层落库与 done 事件下发。</p>
     */
    private RunnerResult replyTeamStream(RunnerContext ctx, String content, StreamListener listener) {
        Assembly assembly = toolAssembler.build(ctx.toolDefs(), ctx.mcpDefs(),
            new ApprovalContext(ctx.sessionId(), ctx.agentId(), ctx.roundId()), ctx.sqlCollector(), ctx.attachments());
        List<ReActAgent> agents = new ArrayList<>();
        ReActAgent registered = null;
        try {
            for (TeamMember member : ctx.teamMembers()) {
                try {
                    agents.add(buildMemberAgent(ctx, member, assembly));
                } catch (Exception e) {
                    throw new ServiceException("协作成员[" + member.agentName() + "]构建失败: " + rootMessage(e));
                }
            }
            BuildResult leaderBuilt;
            try {
                leaderBuilt = buildAgent(ctx, assembly);
            } catch (Exception e) {
                throw new ServiceException("AgentScope 构建失败: " + rootMessage(e));
            }
            ReActAgent leader = leaderBuilt.agent();
            agents.add(leader);
            if (ctx.sessionId() != null) {
                running.put(ctx.sessionId(), leader);
                teamAgents.put(ctx.sessionId(), List.copyOf(agents));
                registered = leader;
            }
            // 顺序接力:首棒输入=历史+用户消息,后续各棒输入=历史+此前所有棒输出(主 Agent 可综合全部成员产出)
            List<Msg> history = historyMsgs(ctx);
            List<Msg> input = new ArrayList<>(history);
            input.add(buildUserMsg(content, ctx.images()));
            List<Msg> outputs = new ArrayList<>();
            Msg finalMsg = null;
            int total = agents.size();
            try {
                for (int i = 0; i < total; i++) {
                    ReActAgent a = agents.get(i);
                    boolean isLeader = i == total - 1;
                    final int idx = i;
                    listener.onTeamAgentStart(idx, total, a.getName(), isLeader ? "leader" : "member");
                    StringBuilder emitted = new StringBuilder();
                    AtomicReference<Msg> finalRef = new AtomicReference<>();
                    a.stream(input,
                            StreamOptions.builder()
                                .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
                                .incremental(true)
                                .includeReasoningChunk(true)
                                .includeReasoningResult(false)
                                .build(),
                            RuntimeContext.empty())
                        .doOnNext(event -> {
                            if (event.getType() == EventType.AGENT_RESULT) {
                                if (event.getMessage() != null) {
                                    finalRef.set(event.getMessage());
                                }
                                return;
                            }
                            Msg msg = event.getMessage();
                            if (msg == null || StringUtils.isBlank(msg.getTextContent())) {
                                return;
                            }
                            String text = msg.getTextContent();
                            String delta;
                            if (text.length() > emitted.length() && text.startsWith(emitted.toString())) {
                                delta = text.substring(emitted.length());
                            } else {
                                delta = text;
                            }
                            if (StringUtils.isNotBlank(delta)) {
                                emitted.append(delta);
                                listener.onTeamAgentDelta(idx, delta);
                            }
                        })
                        .blockLast(callTimeout);
                    finalMsg = finalRef.get();
                    if (finalMsg == null || StringUtils.isBlank(finalMsg.getTextContent())) {
                        throw new ServiceException("AgentScope 团队执行未返回有效回复");
                    }
                    outputs.add(finalMsg);
                    ChatUsage usage = resolveUsage(finalMsg);
                    listener.onTeamAgentDone(idx, finalMsg.getTextContent(),
                        usage == null ? null : usage.getTotalTokens());
                    // 下一棒输入=历史+此前所有棒输出
                    input = new ArrayList<>(history);
                    input.addAll(outputs);
                }
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                throw new ServiceException("AgentScope 团队执行失败: " + rootMessage(e));
            }
            // 各棒轨迹与用量聚合
            List<com.forgeagent.agent.service.AgentRunner.RunnerResult.TeamTraceLine> trace = new ArrayList<>();
            int prompt = 0;
            int completion = 0;
            int totalTokens = 0;
            boolean usagePresent = false;
            for (int i = 0; i < agents.size(); i++) {
                ReActAgent a = agents.get(i);
                boolean isLeader = i == agents.size() - 1;
                Msg own = outputs.size() > i ? outputs.get(i) : null;
                String text = own == null ? null : own.getTextContent();
                ChatUsage u = own == null ? null : resolveUsage(own);
                trace.add(new com.forgeagent.agent.service.AgentRunner.RunnerResult.TeamTraceLine(
                    isLeader ? null : ctx.teamMembers().get(i).agentId(),
                    a.getName(), isLeader ? "leader" : "member",
                    StringUtils.isBlank(text) ? "(无输出)" : text,
                    u == null ? null : u.getTotalTokens()));
                if (u != null) {
                    usagePresent = true;
                    prompt += u.getInputTokens();
                    completion += u.getOutputTokens();
                    totalTokens += u.getTotalTokens();
                }
            }
            String finalText = StringUtils.isBlank(finalMsg.getTextContent())
                ? trace.get(trace.size() - 1).content() : finalMsg.getTextContent();
            return new RunnerResult(finalText,
                usagePresent ? prompt : null, usagePresent ? completion : null, usagePresent ? totalTokens : null,
                "agentscope-react", leaderBuilt.budgetHook() != null && leaderBuilt.budgetHook().isExceeded(),
                false, trace);
        } finally {
            if (registered != null) {
                running.remove(ctx.sessionId(), registered);
                teamAgents.remove(ctx.sessionId());
            }
            assembly.close();
        }
    }

    /**
     * 并行协作(阻塞式,P2 / C2):成员在有界线程池中同时执行,
     * 按成员序号确定性 JOIN 后交给主 Agent 汇总,不依赖线程完成先后。
     */
    private RunnerResult replyTeamParallel(RunnerContext ctx, String content) {
        Assembly assembly = toolAssembler.build(ctx.toolDefs(), ctx.mcpDefs(),
            new ApprovalContext(ctx.sessionId(), ctx.agentId(), ctx.roundId()),
            ctx.sqlCollector(), ctx.attachments());
        List<ReActAgent> agents = new ArrayList<>();
        ReActAgent registered = null;
        try {
            for (TeamMember member : ctx.teamMembers()) {
                agents.add(buildMemberAgent(ctx, member, null));
            }
            BuildResult leaderBuilt = buildAgent(ctx, assembly);
            ReActAgent leader = leaderBuilt.agent();
            agents.add(leader);
            if (ctx.sessionId() != null) {
                running.put(ctx.sessionId(), leader);
                teamAgents.put(ctx.sessionId(), List.copyOf(agents));
                registered = leader;
            }
            List<Msg> history = historyMsgs(ctx);
            List<Msg> input = new ArrayList<>(history);
            input.add(buildUserMsg(content, ctx.images()));
            List<CompletableFuture<Msg>> futures = new ArrayList<>();
            for (int i = 0; i < ctx.teamMembers().size(); i++) {
                ReActAgent memberAgent = agents.get(i);
                List<Msg> memberInput = List.copyOf(input);
                futures.add(CompletableFuture.supplyAsync(
                    () -> memberAgent.call(memberInput).block(callTimeout), TEAM_POOL));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<Msg> outputs = new ArrayList<>();
            for (CompletableFuture<Msg> future : futures) {
                Msg msg = future.join();
                if (msg == null) {
                    throw new ServiceException("AgentScope 并行成员未返回有效回复");
                }
                outputs.add(msg);
            }
            List<Msg> leaderInput = new ArrayList<>(history);
            leaderInput.addAll(outputs);
            Msg finalMsg = leader.call(leaderInput).block(callTimeout);
            if (finalMsg == null) {
                throw new ServiceException("AgentScope 主 Agent 汇总未返回有效回复");
            }
            outputs.add(finalMsg);
            return aggregateTeamResult(ctx, agents, outputs, leaderBuilt);
        } finally {
            if (registered != null) {
                running.remove(ctx.sessionId(), registered);
                teamAgents.remove(ctx.sessionId());
            }
            assembly.close();
        }
    }

    /**
     * 并行协作(流式):各成员并发增量输出,按 index 定位轨迹行;
     * 全部成员 JOIN 后由主 Agent 作为最后一棒流式汇总。
     */
    private RunnerResult replyTeamParallelStream(RunnerContext ctx, String content,
                                                 StreamListener listener) {
        Assembly assembly = toolAssembler.build(ctx.toolDefs(), ctx.mcpDefs(),
            new ApprovalContext(ctx.sessionId(), ctx.agentId(), ctx.roundId()),
            ctx.sqlCollector(), ctx.attachments());
        List<ReActAgent> agents = new ArrayList<>();
        ReActAgent registered = null;
        try {
            for (TeamMember member : ctx.teamMembers()) {
                agents.add(buildMemberAgent(ctx, member, null));
            }
            BuildResult leaderBuilt = buildAgent(ctx, assembly);
            ReActAgent leader = leaderBuilt.agent();
            agents.add(leader);
            int memberCount = ctx.teamMembers().size();
            int total = agents.size();
            if (ctx.sessionId() != null) {
                running.put(ctx.sessionId(), leader);
                teamAgents.put(ctx.sessionId(), List.copyOf(agents));
                registered = leader;
            }
            List<Msg> history = historyMsgs(ctx);
            List<Msg> input = new ArrayList<>(history);
            input.add(buildUserMsg(content, ctx.images()));
            List<CompletableFuture<Msg>> futures = new ArrayList<>();
            for (int i = 0; i < memberCount; i++) {
                final int idx = i;
                ReActAgent memberAgent = agents.get(i);
                List<Msg> memberInput = List.copyOf(input);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    listener.onTeamAgentStart(idx, total, memberAgent.getName(), "member");
                    Msg msg = streamOne(memberAgent, memberInput, idx, listener);
                    String text = msg == null ? "" : msg.getTextContent();
                    ChatUsage usage = msg == null ? null : resolveUsage(msg);
                    listener.onTeamAgentDone(idx, text, usage == null ? null : usage.getTotalTokens());
                    return msg;
                }, TEAM_POOL));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<Msg> outputs = new ArrayList<>();
            for (CompletableFuture<Msg> future : futures) {
                Msg msg = future.join();
                outputs.add(msg == null ? emptyMsg("member") : msg);
            }
            int leaderIdx = memberCount;
            listener.onTeamAgentStart(leaderIdx, total, leader.getName(), "leader");
            List<Msg> leaderInput = new ArrayList<>(history);
            leaderInput.addAll(outputs);
            Msg leaderMsg = streamOne(leader, leaderInput, leaderIdx, listener);
            String leaderText = leaderMsg == null ? "" : leaderMsg.getTextContent();
            ChatUsage leaderUsage = leaderMsg == null ? null : resolveUsage(leaderMsg);
            listener.onTeamAgentDone(leaderIdx, leaderText,
                leaderUsage == null ? null : leaderUsage.getTotalTokens());
            outputs.add(leaderMsg == null ? emptyMsg("leader") : leaderMsg);
            return aggregateTeamResult(ctx, agents, outputs, leaderBuilt);
        } finally {
            if (registered != null) {
                running.remove(ctx.sessionId(), registered);
                teamAgents.remove(ctx.sessionId());
            }
            assembly.close();
        }
    }

    /** 单个内核 Agent 的流式订阅:REASONING 增量回调,AGENT_RESULT 作为最终结果 */
    private Msg streamOne(ReActAgent agent, List<Msg> input, int idx, StreamListener listener) {
        StringBuilder emitted = new StringBuilder();
        AtomicReference<Msg> finalRef = new AtomicReference<>();
        agent.stream(input, StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
                .incremental(true)
                .includeReasoningChunk(true)
                .includeReasoningResult(false)
                .build(),
                RuntimeContext.empty())
            .doOnNext(event -> {
                if (event.getType() == EventType.AGENT_RESULT) {
                    if (event.getMessage() != null) {
                        finalRef.set(event.getMessage());
                    }
                    return;
                }
                Msg msg = event.getMessage();
                if (msg == null || StringUtils.isBlank(msg.getTextContent())) {
                    return;
                }
                String text = msg.getTextContent();
                String delta = text.length() > emitted.length() && text.startsWith(emitted.toString())
                    ? text.substring(emitted.length()) : text;
                if (StringUtils.isNotBlank(delta)) {
                    emitted.append(delta);
                    listener.onTeamAgentDelta(idx, delta);
                }
            })
            .blockLast(callTimeout);
        return finalRef.get();
    }

    /** 汇总各棒输出、用量与轨迹(顺序/并行/角色编排共用) */
    private RunnerResult aggregateTeamResult(RunnerContext ctx, List<ReActAgent> agents,
                                             List<Msg> outputs, BuildResult leaderBuilt) {
        List<com.forgeagent.agent.service.AgentRunner.RunnerResult.TeamTraceLine> trace = new ArrayList<>();
        int prompt = 0;
        int completion = 0;
        int total = 0;
        boolean usagePresent = false;
        for (int i = 0; i < agents.size(); i++) {
            boolean isLeader = i == agents.size() - 1;
            Msg own = outputs.size() > i ? outputs.get(i) : null;
            String text = own == null ? null : own.getTextContent();
            ChatUsage usage = own == null ? null : resolveUsage(own);
            trace.add(new com.forgeagent.agent.service.AgentRunner.RunnerResult.TeamTraceLine(
                isLeader ? null : (i < ctx.teamMembers().size() ? ctx.teamMembers().get(i).agentId() : null),
                agents.get(i).getName(), isLeader ? "leader" : "member",
                StringUtils.isBlank(text) ? "(无输出)" : text,
                usage == null ? null : usage.getTotalTokens()));
            if (usage != null) {
                usagePresent = true;
                prompt += usage.getInputTokens();
                completion += usage.getOutputTokens();
                total += usage.getTotalTokens();
            }
        }
        Msg finalMsg = outputs.isEmpty() ? null : outputs.get(outputs.size() - 1);
        String finalText = finalMsg == null || StringUtils.isBlank(finalMsg.getTextContent())
            ? trace.get(trace.size() - 1).content() : finalMsg.getTextContent();
        return new RunnerResult(finalText,
            usagePresent ? prompt : null, usagePresent ? completion : null, usagePresent ? total : null,
            "agentscope-react", leaderBuilt.budgetHook() != null && leaderBuilt.budgetHook().isExceeded(),
            false, trace);
    }

    private Msg emptyMsg(String name) {
        return Msg.builder().name(name).role(MsgRole.ASSISTANT).textContent("(无输出)").build();
    }

    /**
     * 轮级中断:向本轮全部执行中的内核 Agent 设置中断标志。
     *
     * <p>内核语义:下一次中断检查点触发 {@code handleInterrupt} 生成中断说明消息,
     * call() 正常返回,平台将其作为助手回复落库。团队模式下广播到每个成员与主 Agent,
     * 避免中断后仍有成员继续生成。</p>
     *
     * @return true 表示已对执行中的会话发出中断
     */
    public boolean interrupt(Long sessionId) {
        List<ReActAgent> team = teamAgents.get(sessionId);
        ReActAgent single = running.get(sessionId);
        List<ReActAgent> targets = team != null && !team.isEmpty()
            ? team
            : (single == null ? List.of() : List.of(single));
        if (targets.isEmpty()) {
            return false;
        }
        log.info("[AgentScopeRunner] 会话 {} 请求轮级中断,向 {} 个内核 Agent 广播中断标志",
            sessionId, targets.size());
        for (ReActAgent agent : targets) {
            try {
                agent.interrupt(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent("[系统] 用户中断了本轮对话。")
                    .build());
            } catch (Exception e) {
                log.warn("[AgentScopeRunner] 中断内核 Agent 失败: {}", e.getMessage());
            }
        }
        return true;
    }

    private Msg buildUserMsg(String content, List<AgentRunner.ImageRef> images) {
        if (images == null || images.isEmpty()) {
            return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(content)
                .build();
        }
        // 多模态:文本块 + 图片块(图片以 base64 源接入)
        Msg.Builder builder = Msg.builder().name("user").role(MsgRole.USER);
        if (StringUtils.isNotBlank(content)) {
            builder.content(TextBlock.builder().text(content).build());
        }
        for (AgentRunner.ImageRef image : images) {
            Base64Source source = Base64Source.builder()
                .mediaType(image.mediaType())
                .data(image.base64())
                .build();
            builder.content(ImageBlock.builder().source(source).build());
        }
        return builder.build();
    }

    /**
     * 解析结构化输出 Schema(fasterxml JsonNode,SDK call(msg, JsonNode) 约定)
     */
    private com.fasterxml.jackson.databind.JsonNode parseSchema(String outputSchema) {
        try {
            return FasterxmlMapper.readTree(outputSchema);
        } catch (Exception e) {
            throw new ServiceException("结构化输出Schema解析失败: " + e.getMessage());
        }
    }

    /**
     * 结构化输出回复取 metadata._structured_output 序列化为 JSON 字符串;普通回复取文本内容
     */
    private String resolveReplyText(Msg reply, boolean structured) {
        if (!structured) {
            return reply.getTextContent();
        }
        Object data = reply.getMetadata() == null ? null : reply.getMetadata().get(KEY_STRUCTURED_OUTPUT);
        if (data == null) {
            return reply.getTextContent();
        }
        try {
            return FasterxmlMapper.writeValueAsString(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }

    /**
     * 用量取值:结构化输出优先 metadata._chat_usage(聚合值),否则回复自带 usage
     */
    private ChatUsage resolveUsage(Msg reply) {
        Object usage = reply.getMetadata() == null ? null : reply.getMetadata().get(KEY_CHAT_USAGE);
        if (usage instanceof ChatUsage chatUsage) {
            return chatUsage;
        }
        return reply.getChatUsage();
    }

    /**
     * 构建协作成员内核 Agent(P2 / C2:成员绑定了自己的工具时可带 Toolkit)。
     * 并行模式传 null,避免多个成员并发共享有状态 Toolkit。
     */
    private ReActAgent buildMemberAgent(RunnerContext ctx, TeamMember member) {
        return buildMemberAgent(ctx, member, null);
    }

    private ReActAgent buildMemberAgent(RunnerContext ctx, TeamMember member, Assembly assembly) {
        TeamMember.MemberModel m = member.model();
        String baseUrl = m == null || StringUtils.isBlank(m.baseUrl()) ? ctx.llmBaseUrl() : m.baseUrl();
        String apiKey = m == null || StringUtils.isBlank(m.apiKey()) ? ctx.llmApiKey() : m.apiKey();
        String modelName = m == null || StringUtils.isBlank(m.modelName()) ? ctx.llmModel() : m.modelName();
        Double temperature = m == null || m.temperature() == null ? ctx.temperature() : m.temperature();
        Integer maxTokens = m == null || m.maxTokens() == null ? ctx.maxTokens() : m.maxTokens();
        String sysPrompt = StringUtils.isBlank(member.sysPrompt())
            ? "你是团队协作成员[" + member.agentName() + "],请基于你的专长处理用户请求,输出处理结论。"
            : member.sysPrompt();
        Model model = AgentScopeModelFactory.build(baseUrl, apiKey, modelName, temperature, maxTokens);
        ReActAgent.Builder builder = ReActAgent.builder()
            .name(member.agentName())
            .model(model)
            .maxIters(maxIters)
            .sysPrompt(sysPrompt);
        if (member.allowTools() && assembly != null
            && assembly.toolkit().getToolNames() != null
            && !assembly.toolkit().getToolNames().isEmpty()) {
            builder.toolkit(assembly.toolkit());
        }
        return builder.build();
    }

    /**
     * 模型卡路由与协议适配统一由 {@link AgentScopeModelFactory} 处理
     */
    private Model buildModel(RunnerContext ctx) {
        return AgentScopeModelFactory.build(ctx.llmBaseUrl(), ctx.llmApiKey(), ctx.llmModel(),
            ctx.temperature(), ctx.maxTokens());
    }

    /**
     * 构建结果:内核 Agent 与可选的预算钩子
     */
    private record BuildResult(ReActAgent agent, TokenBudgetHook budgetHook) {
    }

    /**
     * 会话历史转内核消息列表(2.x 无外部 Memory,历史随输入注入)
     */
    private List<Msg> historyMsgs(RunnerContext ctx) {
        List<Msg> msgs = new ArrayList<>();
        List<ChatTurn> history = ctx.history();
        if (history != null) {
            for (ChatTurn turn : history) {
                if (StringUtils.isBlank(turn.content())) {
                    continue;
                }
                MsgRole role = "assistant".equalsIgnoreCase(turn.role()) ? MsgRole.ASSISTANT : MsgRole.USER;
                msgs.add(Msg.builder()
                    .name(turn.role())
                    .role(role)
                    .textContent(turn.content())
                    .build());
            }
        }
        return msgs;
    }

    /**
     * 历史消息 + 本轮用户消息组装为内核输入
     */
    private List<Msg> withHistory(RunnerContext ctx, Msg userMsg) {
        List<Msg> input = historyMsgs(ctx);
        input.add(userMsg);
        return input;
    }

    private BuildResult buildAgent(RunnerContext ctx, Assembly assembly) {
        String systemPrompt = ctx.systemPrompt();
        if (StringUtils.isNotBlank(ctx.retrievedContext())) {
            systemPrompt = (StringUtils.isBlank(systemPrompt) ? "" : systemPrompt + "\n\n")
                + "以下为知识库检索结果与用户上传文件的相关内容,请结合其中内容回答;若未命中相关内容或内容不足,请明确说明后再基于通用知识回答;"
                + "文件内容不完整时可调用 read_attachment 工具按页/工作表/关键词读取:\n"
                + ctx.retrievedContext();
        }
        // 注入恒定时区说明(不破坏 KV Cache 前缀缓存);动态时间在用户消息中注入
        systemPrompt = (StringUtils.isBlank(systemPrompt) ? "" : systemPrompt + "\n\n")
            + AgentRunner.timeZonePrompt();
        ReActAgent.Builder builder = ReActAgent.builder()
            .name(ctx.agentName())
            .model(buildModel(ctx))
            .maxIters(maxIters);
        if (StringUtils.isNotBlank(systemPrompt)) {
            builder.sysPrompt(systemPrompt);
        }
        if (assembly.toolkit().getToolNames() != null && !assembly.toolkit().getToolNames().isEmpty()) {
            builder.toolkit(assembly.toolkit());
        }
        if (ctx.tokenBudget() != null && ctx.tokenBudget() > 0) {
            TokenBudgetHook hook = new TokenBudgetHook(ctx.tokenBudget(),
                ctx.usedTokens() == null ? 0L : ctx.usedTokens());
            builder.hooks(List.of(hook));
            return new BuildResult(builder.build(), hook);
        }
        return new BuildResult(builder.build(), null);
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
