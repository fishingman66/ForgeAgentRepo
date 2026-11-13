package com.forgeagent.agent.service.workflow.run;

/**
 * 一次图执行的结果:success 正常结束;pendingNode 非空表示触发人工审批暂停。
 */
public record ExecutionOutcome(boolean success, String pendingNode, String pendingNodeName,
                               Long approvalId) {
}
