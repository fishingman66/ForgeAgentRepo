package com.forgeagent.agent.service.runner;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 预算内核钩子
 *
 * <p>挂载在 AgentScope ReActAgent 上,实现 Agent 级累计预算的轮内硬约束:
 * <ul>
 *   <li>{@code POST_REASONING}:每次模型推理完成累计真实 usage,累计(含历史落库用量)
 *       超过预算时调用 {@code stopAgent()} 终止 ReAct 循环,不再继续工具调用与后续推理;</li>
 *   <li>{@code POST_CALL}:若预算已超限,把最终回复改写为预算超限系统提示,由平台落库。</li>
 * </ul>
 * 发送前的预检(历史用量已超限直接拦截)在平台侧 {@code AgentChatServiceImpl} 完成。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
public final class TokenBudgetHook implements Hook {

    private final long budget;
    private final long initialUsed;
    private final AtomicLong used;
    private final AtomicBoolean exceeded = new AtomicBoolean(false);

    /**
     * @param budget      Agent 累计总预算(总 tokens)
     * @param initialUsed 发起本轮前已落库的累计用量
     */
    public TokenBudgetHook(long budget, long initialUsed) {
        this.budget = budget;
        this.initialUsed = initialUsed;
        this.used = new AtomicLong(initialUsed);
    }

    /**
     * 本轮执行结束后预算是否已超限
     */
    public boolean isExceeded() {
        return exceeded.get();
    }

    /**
     * 预算超限系统提示(含用量明细,作为助手回复落库)
     */
    public static String exceedMessage(long used, long budget) {
        return "[系统] Token 预算已耗尽:累计用量 " + used + " tokens 已达上限 " + budget
            + " tokens,本轮执行被终止。请联系管理员调整预算后重试。";
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent e) {
            return Mono.fromCallable(() -> onPostReasoning(e)).map(m -> (T) m);
        }
        if (event instanceof PostCallEvent e) {
            return Mono.fromCallable(() -> onPostCall(e)).map(m -> (T) m);
        }
        return Mono.just(event);
    }

    private PostReasoningEvent onPostReasoning(PostReasoningEvent event) {
        Msg reasoning = event.getReasoningMessage();
        ChatUsage usage = reasoning == null ? null : reasoning.getChatUsage();
        if (usage != null) {
            long now = used.addAndGet(usage.getTotalTokens());
            log.debug("[TokenBudgetHook] 累计用量 {}/{}", now, budget);
        }
        if (used.get() >= budget) {
            exceeded.set(true);
            event.stopAgent();
            log.info("[TokenBudgetHook] 累计用量 {} 达到预算 {},终止 ReAct 循环", used.get(), budget);
        }
        return event;
    }

    private PostCallEvent onPostCall(PostCallEvent event) {
        if (exceeded.get()) {
            event.setFinalMessage(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent(exceedMessage(used.get(), budget))
                .build());
        }
        return event;
    }
}
