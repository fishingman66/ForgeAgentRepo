package com.forgeagent.callcenter.engine;

import java.util.List;
import java.util.Locale;

/**
 * 不允许打断词组匹配器(C2):ASR partial 文本命中语气词(嗯/哦/好的……)时
 * 忽略本次打断,避免 caller 刚开口的语气词就掐断机器人话术——
 * 对齐 SmartCall ivr_answer_no_interrupt 的精确/模糊双模式。
 *
 * <p>精确模式:partial trim 后**全等**词组;模糊模式:partial **包含**词组。
 * 空文本/纯标点视为未命中(不给打断任何豁免)。</p>
 */
public final class NoInterruptMatcher {

    private final List<String> exactWords;
    private final List<String> fuzzyWords;

    public NoInterruptMatcher(List<String> exactWords, List<String> fuzzyWords) {
        this.exactWords = exactWords == null ? List.of() : exactWords.stream()
            .filter(w -> w != null && !w.isBlank()).map(w -> w.trim().toLowerCase(Locale.ROOT)).toList();
        this.fuzzyWords = fuzzyWords == null ? List.of() : fuzzyWords.stream()
            .filter(w -> w != null && !w.isBlank()).map(w -> w.trim().toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * 判定 partial 是否应豁免打断。
     *
     * @param partial ASR 中间文本,空串/纯空白不豁免
     * @return true = 命中语气词,应忽略打断
     */
    public boolean shouldSkip(String partial) {
        if (partial == null || partial.isBlank()) {
            return false;
        }
        String text = partial.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        for (String word : exactWords) {
            if (text.equals(word)) {
                return true;
            }
        }
        for (String word : fuzzyWords) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public List<String> exactWords() {
        return exactWords;
    }

    public List<String> fuzzyWords() {
        return fuzzyWords;
    }
}
