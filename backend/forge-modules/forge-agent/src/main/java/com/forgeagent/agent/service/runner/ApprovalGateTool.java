package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.common.json.utils.JsonUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

/**
 * 工具审批门(HITL 装饰器)
 *
 * <p>按平台审批规则包装内核 AgentTool:
 * auto 直接执行;ask 创建审批单并同步等待人工处理(审批中心通过/拒绝),
 * 等待期间可被轮级中断取消;deny 直接返回拒绝结果,模型将感知并被引导改用其他方式。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
record ApprovalGateTool(AgentTool delegate, String toolSource, String serverName,
                        String approvalMode, Integer approvalTimeout,
                        ApprovalContext ctx, IAgentApprovalService approvalService) implements AgentTool {

    /**
     * 审批上下文(创建审批单所需)
     */
    record ApprovalContext(Long sessionId, Long agentId, String roundId) {
    }

    private static final long DEFAULT_TIMEOUT_SECONDS = 120;

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String mode = approvalMode == null ? "auto" : approvalMode;
        return switch (mode) {
            case "deny" -> Mono.just(ToolResultBlock.error(
                "工具 [" + getName() + "] 已被平台审批策略禁用,请勿再次尝试调用,请直接说明或改用其他方式。"));
            case "ask" -> callWithApproval(param);
            default -> delegate.callAsync(param);
        };
    }

    /**
     * ask 模式:创建审批单 → 轮询等待人工处理 → 通过则执行,拒绝/取消/超时返回说明
     */
    private Mono<ToolResultBlock> callWithApproval(ToolCallParam param) {
        String callArgs;
        try {
            callArgs = JsonUtils.toJsonString(param.getInput() == null ? Map.of() : param.getInput());
        } catch (Exception e) {
            callArgs = "{}";
        }
        final Long approvalId;
        try {
            approvalId = approvalService.create(ctx.sessionId(), ctx.agentId(), ctx.roundId(),
                getName(), toolSource, serverName, callArgs);
        } catch (Exception e) {
            log.error("[ApprovalGate] 创建审批单失败: {}", e.getMessage(), e);
            return Mono.just(ToolResultBlock.error("审批系统异常,工具调用被中止: " + e.getMessage()));
        }
        long timeoutSeconds = approvalTimeout == null || approvalTimeout <= 0
            ? DEFAULT_TIMEOUT_SECONDS : approvalTimeout;
        log.info("[ApprovalGate] 工具 [{}] 需人工审批, approvalId={}, 等待 {}s",
            getName(), approvalId, timeoutSeconds);
        return Flux.interval(Duration.ofSeconds(1))
            .concatMap(i -> queryStatus(approvalId))
            .filter(status -> status != null && !"pending".equals(status))
            .next()
            .timeout(Duration.ofSeconds(timeoutSeconds),
                Mono.fromCallable(() -> {
                    approvalService.markExpired(approvalId);
                    return "expired";
                }).subscribeOn(Schedulers.boundedElastic()))
            .flatMap(status -> {
                if ("approved".equals(status)) {
                    log.info("[ApprovalGate] 工具 [{}] 审批通过,执行调用", getName());
                    return delegate.callAsync(param);
                }
                return Mono.just(rejectedBlock(status));
            });
    }

    private Mono<String> queryStatus(Long approvalId) {
        return Mono.fromCallable(() -> approvalService.getStatus(approvalId))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorReturn("pending");
    }

    private ToolResultBlock rejectedBlock(String status) {
        String text = switch (status) {
            case "rejected" -> "用户在人工审批中拒绝了工具 [" + getName() + "] 的调用,请勿再次尝试该工具,"
                + "请直接基于已有信息回答或说明需要该操作才能继续。";
            case "cancelled" -> "用户中断了本轮对话,工具 [" + getName() + "] 的调用审批已被取消。";
            default -> "工具 [" + getName() + "] 的审批等待超时(" + status + "),调用未执行。";
        };
        log.info("[ApprovalGate] 工具 [{}] 未获执行许可: {}", getName(), status);
        return ToolResultBlock.error(text);
    }
}
