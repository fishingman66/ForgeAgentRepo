package com.forgeagent.agent.api.customerservice;

/**
 * 运行归属校验契约(S0)。
 *
 * <p>替代 {@code AgentChatController.isOwnedRun} 里硬编码的 LoginHelper 比对:
 * 控制台会话按登录用户,客服会话按访客ID + 渠道编码。</p>
 */
public interface CsRunOwnership {

    /** 控制台场景:run 是否属于当前登录用户。 */
    boolean isOwnedByUser(String runId, Long userId);

    /** 客服场景:run 是否属于指定访客与渠道。 */
    boolean isOwnedByVisitor(String runId, Long visitorId, String channelCode);
}
