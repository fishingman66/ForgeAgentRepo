package com.forgeagent.customerservice.acd.lineup;

import com.forgeagent.callcenter.common.acd.LineupStrategy;

import java.util.Map;

/**
 * 排队策略(FIFO / VIP / 自定义),对齐参考项目 DefaultLineupStrategy / VipLineupStrategy。
 *
 * <p>priority 取值来自 CsQueue.priority(VARCHAR,默认 NORMAL)与 CsTicket.priority,
 * 定义 VIP/URGENT/HIGH 三档高于 NORMAL,未知值按 NORMAL(零回退)。</p>
 */
public final class LineupStrategies {

    private LineupStrategies() {
    }

    /** 1 FIFO:越早入队越优先(参考 -queueStartTime)。 */
    public static final LineupStrategy FIFO = new LineupStrategy() {
        @Override
        public String code() {
            return "fifo";
        }

        @Override
        public long score(String priority, long enqueuedAtMs) {
            return -enqueuedAtMs;
        }
    };

    /** 2 VIP:优先级档位为主,入队时间为次(参考 vipLevel - queueStartTime)。 */
    public static final LineupStrategy VIP = new LineupStrategy() {
        @Override
        public String code() {
            return "vip";
        }

        @Override
        public long score(String priority, long enqueuedAtMs) {
            return level(priority) * 1_000_000_000L - enqueuedAtMs;
        }
    };

    /** 3 自定义:表达式留口(无 SpEL 依赖时按 VIP 兜底,不抛异常)。 */
    public static final LineupStrategy CUSTOM = new LineupStrategy() {
        @Override
        public String code() {
            return "custom";
        }

        @Override
        public long score(String priority, long enqueuedAtMs) {
            return VIP.score(priority, enqueuedAtMs);
        }
    };

    private static final Map<String, LineupStrategy> BY_CODE = Map.of(
        "fifo", FIFO, "vip", VIP, "custom", CUSTOM);

    public static LineupStrategy fromCode(String code) {
        if (code == null || code.isBlank()) {
            return FIFO;
        }
        LineupStrategy s = BY_CODE.get(code.trim().toLowerCase());
        return s == null ? FIFO : s;
    }

    /** 优先级档位:NORMAL=0, HIGH=1, URGENT=2, VIP=3;未知=0。 */
    static int level(String priority) {
        if (priority == null) {
            return 0;
        }
        return switch (priority.trim().toUpperCase()) {
            case "VIP" -> 3;
            case "URGENT" -> 2;
            case "HIGH" -> 1;
            default -> 0;
        };
    }
}
