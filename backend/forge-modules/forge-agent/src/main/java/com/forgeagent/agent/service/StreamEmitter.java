package com.forgeagent.agent.service;

/**
 * SSE 流式事件发射器抽象(由 Controller 以 Servlet 输出流实现)
 *
 * <p>{@link #bindRun(String)} / {@link #finish()} 为 P0 运行时扩展点,
 * 默认空实现以兼容既有 lambda 发射器与调试调用方。</p>
 *
 * @author ForgeAgent
 */
public interface StreamEmitter {

    /**
     * 发送一个 SSE 事件
     *
     * @param event 事件名(meta/delta/done/error/usage/team_*)
     * @param data  事件负载(将序列化为 JSON)
     */
    void send(String event, Object data);

    /**
     * 绑定本轮运行 ID(事件缓冲、取消令牌与 Trace 均以此为键)。
     * 仅 {@code RunStreamEmitter} 需要实现。
     *
     * @param runId     运行 ID
     * @param sessionId 会话 ID(跨实例取消时定位本轮运行)
     */
    default void bindRun(String runId, Long sessionId) {
    }

    /**
     * 本轮流结束收口(标记终态、释放运行期注册)。
     */
    default void finish() {
    }
}
