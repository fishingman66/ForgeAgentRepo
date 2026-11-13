package com.forgeagent.agent.service.workflow.run;

import com.forgeagent.agent.domain.AgentWorkflowRun;
import com.forgeagent.agentcore.domain.AgentInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次图执行的上下文(dryRun / 步数上限 / 执行身份 / 入参)。
 *
 * <p>原为 {@code AgentWorkflowServiceImpl} 的私有 record;拆分节点执行器后提升为
 * 跨包共享类型,字段与语义完全不变。</p>
 */
public record WorkflowRunContext(AgentInfo agent, AgentWorkflowRun run, boolean fromTrigger,
                                 boolean dryRun, int maxSteps, AtomicInteger steps, String inputs) {
}
