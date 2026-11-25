package com.forgeagent.callcenter.common.acd;

/** 坐席分配策略(零依赖 SPI):分数越大越优先,与参考项目 AgentStrategy 同语义。 */
public interface AgentStrategy {

    /** 策略编码,与配置 agent-strategy 对应。 */
    String code();

    /**
     * 计算候选坐席的优先级分数。
     *
     * <p>无数据源时(readyTimeMs/answerTimes/talkTimeMs/afterTimeMs 为 null),
     * 依赖该字段的策略必须降级到 {@link #fallback()} 的分数,不得抛异常或返回随机值。</p>
     */
    long score(AcdCandidate candidate);

    /** 每轮分配开始时调用一次(轮选策略据此推进游标);默认空实现。 */
    default void beginRound() {
    }

    /** 无数据源时的降级分数基准。 */
    default long fallback() {
        return Long.MIN_VALUE;
    }
}
