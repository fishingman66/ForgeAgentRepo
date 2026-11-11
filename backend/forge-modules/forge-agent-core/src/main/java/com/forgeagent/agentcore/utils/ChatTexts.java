package com.forgeagent.agentcore.utils;

/**
 * 对话文本小工具
 *
 * @author ForgeAgent
 */
public final class ChatTexts {

    private ChatTexts() {
    }

    /**
     * 截断文本(先去首尾空白,超长截断)
     */
    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}