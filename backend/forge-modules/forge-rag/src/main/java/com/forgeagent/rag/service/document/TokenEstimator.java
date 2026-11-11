package com.forgeagent.rag.service.document;

/**
 * Token 数估算:中文等 CJK 字符按 1 字 1 token,连续字母/数字按 1 词 1 token,其余符号忽略。
 * 用于分块大小展示与上下文预算评估,非精确 tokenizer。
 *
 * @author ForgeAgent
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 128) {
                if (!inWord) {
                    count++;
                    inWord = true;
                }
            } else {
                inWord = false;
                if (!Character.isWhitespace(c)) {
                    count++;
                }
            }
        }
        return count;
    }
}
