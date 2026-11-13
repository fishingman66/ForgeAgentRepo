package com.forgeagent.callcenter.engine;

/**
 * ASR 结果监听器(C2):partial 用于打断判定与实时弹屏,final 用于转写落库与意图分流。
 */
public interface AsrListener {

    /** 中间结果(非终稿,可能被后续修正);文本可为空串。 */
    default void onPartial(String text) {
    }

    /** 终稿:一句话说完。confidence 0~1,厂商不给时用 -1 表示未知。 */
    void onFinal(String text, double confidence);

    /** 引擎故障(断连/超时/配额):编排层降级,不得让通话主链路崩溃。 */
    void onError(Exception cause);
}
