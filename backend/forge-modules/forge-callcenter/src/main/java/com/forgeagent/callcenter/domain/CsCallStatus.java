package com.forgeagent.callcenter.domain;

/**
 * 呼叫状态机(V1,方案 v2 第七章 8 态)。与工单七态分表分枚举,单向映射,HOLD 不映射 ON_HOLD。
 */
public enum CsCallStatus {

    RINGING(1),
    ANSWERED(2),
    BOT_SERVING(3),
    WAITING_AGENT(4),
    AGENT_SERVING(5),
    HOLD(6),
    WRAP_UP(7),
    ENDED(8);

    private final int rank;

    CsCallStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static CsCallStatus of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 单调推进规则:目标态为空或 rank 不高于当前态时不推进,
     * 迟到的 CDR / 乱序事件不得回退状态(V0 契约与方案 5.5 一致)。
     */
    public static String advance(String current, String target) {
        CsCallStatus from = of(current);
        CsCallStatus to = of(target);
        if (to == null || (from != null && to.rank <= from.rank)) {
            return current;
        }
        return to.name();
    }
}
