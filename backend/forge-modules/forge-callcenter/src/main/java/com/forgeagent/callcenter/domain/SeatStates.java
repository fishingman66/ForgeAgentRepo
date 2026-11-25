package com.forgeagent.callcenter.domain;

import java.util.Map;

/**
 * 坐席就绪态规范值(P1-2)。从 5 态扩展到含咨询/保持/话后/质检类状态,但保持向前兼容:
 *
 * <p>历史值 OFFLINE/READY/PAUSE/TALKING/WRAP_UP 原样保留;新增 CONSULT/HOLD/AFTER/
 * MONITOR/INSERT/LISTEN/WHISPER。坐席状态存的是 String(CsCallSeatState.readyState),
 * 本类只做"常量 + 规范化 + rank",<b>不引入 enum 强类型</b>,以免破坏厂商事件透传链路。</p>
 *
 * <p>rank 只用于"日志乱序不回退"的判定(与通话 8 态状态机 CsCallStatus 相互独立),
 * rank 越大越新;未知状态 rank=0,不覆盖已知状态。</p>
 */
public final class SeatStates {

    private SeatStates() {
    }

    public static final String OFFLINE = "OFFLINE";
    public static final String READY = "READY";
    public static final String PAUSE = "PAUSE";
    public static final String TALKING = "TALKING";
    public static final String WRAP_UP = "WRAP_UP";

    /** 新增:咨询/保持/话后/质检类。 */
    public static final String CONSULT = "CONSULT";
    public static final String HOLD = "HOLD";
    public static final String AFTER = "AFTER";
    public static final String MONITOR = "MONITOR";
    public static final String INSERT = "INSERT";
    public static final String LISTEN = "LISTEN";
    public static final String WHISPER = "WHISPER";

    private static final Map<String, Integer> RANK = Map.ofEntries(
        Map.entry(OFFLINE, 1),
        Map.entry(READY, 2),
        Map.entry(PAUSE, 3),
        Map.entry(TALKING, 4),
        Map.entry(CONSULT, 5),
        Map.entry(HOLD, 6),
        Map.entry(WRAP_UP, 7),
        Map.entry(AFTER, 8),
        Map.entry(MONITOR, 9),
        Map.entry(INSERT, 10),
        Map.entry(LISTEN, 11),
        Map.entry(WHISPER, 12)
    );

    /** 规范化:大小写归一 + 空白裁剪;未知值原样大写返回(不丢厂商扩展态)。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        return RANK.containsKey(v) ? v : v;
    }

    /** 是否为已知规范态。 */
    public static boolean known(String state) {
        return state != null && RANK.containsKey(state);
    }

    /** rank:未知=0;用于乱序日志判定,不参与通话状态机。 */
    public static int rank(String state) {
        if (state == null) {
            return 0;
        }
        return RANK.getOrDefault(state.trim().toUpperCase(), 0);
    }

    /** 新状态是否可覆盖旧状态(单调:新 rank >= 旧 rank;未知旧态视为可覆盖)。 */
    public static boolean advances(String oldState, String newState) {
        int o = rank(oldState);
        int n = rank(newState);
        if (n == 0) {
            return false; // 未知新态不覆盖已知态
        }
        return o == 0 || n >= o;
    }
}
