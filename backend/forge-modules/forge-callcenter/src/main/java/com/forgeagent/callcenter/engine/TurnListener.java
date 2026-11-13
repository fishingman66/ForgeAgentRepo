package com.forgeagent.callcenter.engine;

/**
 * 级联轮次监听器(C2):CascadeTurnEngine 的上行事件出口。
 * C3 IVR 节点引擎据 finalUtterance 驱动下一节点;C4 洞察管道消费同一事件写转写。
 */
public interface TurnListener {

    /** caller 终稿(一句话说完):speaker 固定 caller。 */
    void onFinalUtterance(String text, double confidence);

    /** ASR 引擎故障:编排层已降级(轮次终止不崩链路),由上层决定重试或转人工。 */
    default void onAsrError(Exception cause) {
    }

    /** barge-in 发生(caller 打断话术播报):partial 为触发打断时的中间文本。 */
    default void onBargeIn(String partial) {
    }
}
