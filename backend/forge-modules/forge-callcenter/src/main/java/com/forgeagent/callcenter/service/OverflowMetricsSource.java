package com.forgeagent.callcenter.service;

/** 溢出指标来源(SPI):解耦评估器与具体 Mapper,便于单测与替换。 */
public interface OverflowMetricsSource {

    /** 当前 workspace 的排队长度。 */
    int queueSize(Long workspaceId);

    /** 当前 workspace 排队最久的等待秒数;无排队返回 0。 */
    long maxWaitSeconds(Long workspaceId);

    /** 当前 workspace 当日呼损率百分比(0-100);无数据返回 0。 */
    int abandonRatePercent(Long workspaceId);
}
