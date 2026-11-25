package com.forgeagent.callcenter.common.acd;

/** 排队顺序策略(零依赖 SPI):分数越大越先出队。 */
public interface LineupStrategy {

    /** 策略编码,与配置 lineup-strategy 对应。 */
    String code();

    /** 计算排队项优先级;priority=null 视为 NORMAL,enqueuedAtMs 为入队时间戳。 */
    long score(String priority, long enqueuedAtMs);
}
