package com.forgeagent.agent.api.customerservice;

/**
 * 工作流 → 客服域转人工契约(C1)。
 *
 * <p>{@code human_transfer} 节点在 forge-agent 内执行,而建单逻辑属于客服域;
 * 依赖方向是 cs → agent,故由本接口 + 客服侧实现反向注入(未启用客服模块时为 null)。</p>
 */
public interface CsHandoffFacade {

    /**
     * 按会话转人工建单。
     *
     * @param workspaceId    发起方空间,用于校验会话归属(可为 null 表示不校验)
     * @param conversationId 会话 ID
     * @param reason         转人工原因
     * @throws IllegalArgumentException 会话不存在或不属于该空间
     */
    HandoffResult transfer(Long workspaceId, Long conversationId, String reason);

    /** 建单结果最小集(节点只依赖这三个字段)。 */
    record HandoffResult(Long ticketId, String ticketNo, String status) {
    }
}
