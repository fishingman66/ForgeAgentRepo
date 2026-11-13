package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.callcenter.engine.ChannelControl;
import com.forgeagent.callcenter.engine.CascadeTurnEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 首批 9 个节点执行器(C3):全部无状态,会话资源经 NodeContext 变量传入——
 * 约定变量键:turnEngine(级联引擎)/callerChannel(阻塞收音收号)/channel(通道命令)/
 * workspaceId/callerUtterance(最近 caller 终稿)/recentUtterances(滚动窗口)。
 *
 * <p>分支语义:answer 超时走 silenceNextId;intention 未命中走 otherNextId;
 * agent 未命中走 noAnswerNextId;transfer/hangup 发完命令即终止循环(nextId=null),
 * 接通/挂断由事件回流经既有 8 态状态机处理。</p>
 */
@Slf4j
public final class IvrExecutors {

    private IvrExecutors() {
    }

    private static CascadeTurnEngine turnEngine(NodeContext ctx) {
        Object value = ctx.getVar("turnEngine");
        if (!(value instanceof CascadeTurnEngine engine)) {
            throw new IllegalStateException("上下文缺少 turnEngine,检查会话驱动器装配");
        }
        return engine;
    }

    private static CascadeEngineFactory.CallerChannel callerChannel(NodeContext ctx) {
        Object value = ctx.getVar("callerChannel");
        if (!(value instanceof CascadeEngineFactory.CallerChannel channel)) {
            throw new IllegalStateException("上下文缺少 callerChannel,检查会话驱动器装配");
        }
        return channel;
    }

    private static ChannelControl channelControl(NodeContext ctx) {
        Object value = ctx.getVar("channel");
        if (!(value instanceof ChannelControl channel)) {
            throw new IllegalStateException("上下文缺少 channel,检查会话驱动器装配");
        }
        return channel;
    }

    private static String callerUtterance(NodeContext ctx) {
        return ctx.str("callerUtterance");
    }

    /** start:只跳转。 */
    public static class StartExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "start";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) {
            return NodeOutcome.next(node.getNextId());
        }
    }

    /** say:SpEL 渲染话术 → TTS 播报(流式,不阻塞等待播完)。 */
    public static class SayExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "say";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) {
            IvrNode.Say say = cast(ctx, node, IvrNode.Say.class);
            turnEngine(ctx).speak(ctx.render(say.getText()));
            return NodeOutcome.next(node.getNextId());
        }
    }

    /** answer:阻塞收音(prompt 可空),超时走静默分支。 */
    public static class AnswerExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "answer";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) throws Exception {
            IvrNode.Answer answer = cast(ctx, node, IvrNode.Answer.class);
            CascadeEngineFactory.CallerChannel channel = callerChannel(ctx);
            String prompt = answer.getPrompt() == null ? "" : ctx.render(answer.getPrompt());
            String utterance = channel.awaitUtterance(prompt, answer.getTimeoutMs() == null ? 8000 : answer.getTimeoutMs());
            if (utterance == null || utterance.isBlank()) {
                // 静默:更新最近终稿为空,走静默分支(未配则 nextId)
                ctx.setVar("callerUtterance", null);
                String silence = answer.getSilenceNextId() == null ? node.getNextId() : answer.getSilenceNextId();
                log.debug("[CC][IVR] answer 静默 → {}", silence);
                return NodeOutcome.next(silence);
            }
            ctx.setVar("callerUtterance", utterance);
            return NodeOutcome.next(node.getNextId());
        }
    }

    /** received:阻塞收号(digits 进变量 dtmfDigits)。 */
    public static class ReceivedExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "received";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) throws Exception {
            IvrNode.Received received = cast(ctx, node, IvrNode.Received.class);
            CascadeEngineFactory.CallerChannel channel = callerChannel(ctx);
            String prompt = received.getPrompt() == null ? "" : ctx.render(received.getPrompt());
            if (!prompt.isBlank()) {
                turnEngine(ctx).speak(prompt);
            }
            String digits = channel.awaitDtmf(
                received.getEndKey() == null ? "#" : received.getEndKey(),
                received.getMaxDigits() == null ? 8 : received.getMaxDigits(),
                received.getTimeoutMs() == null ? 8000 : received.getTimeoutMs());
            ctx.setVar("dtmfDigits", digits);
            return NodeOutcome.next(node.getNextId());
        }
    }

    /** intention:分类(text 可空,默认 caller 终稿)→ 按 code 跳分支。 */
    public static class IntentionExecutor implements NodeExecutor {
        private final Supplier<IntentionClassifier> classifier;

        public IntentionExecutor(Supplier<IntentionClassifier> classifier) {
            this.classifier = classifier;
        }

        @Override
        public String type() {
            return "intention";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) {
            IvrNode.Intention intention = cast(ctx, node, IvrNode.Intention.class);
            String text = intention.getText() == null || intention.getText().isBlank()
                ? callerUtterance(ctx) : ctx.render(intention.getText());
            Long workspaceId = asLong(ctx.getVar("workspaceId"), 1L);
            IntentionClassifier.Result result = classifier.get().classify(
                workspaceId, text, callerHistory(ctx));
            String code = result == null ? null : result.intentCode();
            ctx.setVar("intent", code);
            ctx.setVar("intentConfidence", result == null ? -1.0 : result.confidence());
            Map<String, String> branches = intention.getBranches();
            if (code != null && branches != null && branches.containsKey(code)) {
                return NodeOutcome.next(branches.get(code));
            }
            return NodeOutcome.next(intention.getOtherNextId());
        }
    }

    /** agent:知识库问答(question 可空,默认 caller 终稿)→ answer 变量或 noAnswer 分支。 */
    public static class AgentExecutor implements NodeExecutor {
        private final Supplier<KnowledgeAnswerer> answerer;

        public AgentExecutor(Supplier<KnowledgeAnswerer> answerer) {
            this.answerer = answerer;
        }

        @Override
        public String type() {
            return "agent";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) {
            IvrNode.Agent agent = cast(ctx, node, IvrNode.Agent.class);
            String question = agent.getQuestion() == null || agent.getQuestion().isBlank()
                ? callerUtterance(ctx) : ctx.render(agent.getQuestion());
            Long workspaceId = asLong(ctx.getVar("workspaceId"), 1L);
            String answer = answerer.get().answer(workspaceId, question);
            if (answer == null || answer.isBlank()) {
                return NodeOutcome.next(agent.getNoAnswerNextId());
            }
            ctx.setVar("agentAnswer", answer);
            turnEngine(ctx).speak(answer);
            return NodeOutcome.next(node.getNextId());
        }
    }

    /** condition:SpEL 布尔表达式分支。 */
    public static class ConditionExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "condition";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) {
            IvrNode.Condition condition = cast(ctx, node, IvrNode.Condition.class);
            boolean hit = ctx.evalBool(condition.getExpression());
            return NodeOutcome.next(hit ? condition.getTrueNextId() : condition.getFalseNextId());
        }
    }

    /** transfer:发转人工命令(队列名 SpEL)后终止循环,接通由事件回流。 */
    public static class TransferExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "transfer";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) {
            IvrNode.Transfer transfer = cast(ctx, node, IvrNode.Transfer.class);
            String queue = ctx.render(transfer.getQueue());
            channelControl(ctx).transferToQueue(queue);
            log.info("[CC][IVR] 转人工 → queue={} callId={}", queue, ctx.str("callId"));
            return NodeOutcome.end();
        }
    }

    /** hangup:发挂机命令后终止循环。 */
    public static class HangupExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "hangup";
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) {
            IvrNode.Hangup hangup = cast(ctx, node, IvrNode.Hangup.class);
            String cause = hangup.getCause() == null ? "NORMAL_CLEARING" : hangup.getCause();
            channelControl(ctx).hangup(cause);
            return NodeOutcome.end();
        }
    }

    private static Long asLong(Object value, Long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> callerHistory(NodeContext ctx) {
        Object value = ctx.getVar("recentUtterances");
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
