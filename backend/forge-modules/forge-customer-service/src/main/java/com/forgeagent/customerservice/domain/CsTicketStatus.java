package com.forgeagent.customerservice.domain;

/** 工单七态(C1),移植参考项目 TicketStatus,单表实现无兼容层。 */
public enum CsTicketStatus {

    AI_SERVING,
    WAITING_AGENT,
    PROCESSING,
    ON_HOLD,
    WAITING_CONFIRM,
    RESOLVED,
    CLOSED;

    public boolean isEnded() {
        return this == RESOLVED || this == CLOSED;
    }

    public static CsTicketStatus of(String name) {
        for (CsTicketStatus value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知工单状态: " + name);
    }
}
