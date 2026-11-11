package com.forgeagent.rag.service.retrieve;

/**
 * 子块在父块内的偏移定位(纯函数)。
 *
 * <p>为什么单独抽出来:偏移算错只会在"禁用子块裁剪"这类少数路径上暴露,
 * 且只在真实文档上复现——必须有可单测的入口,而不是埋在异步处理器里。</p>
 *
 * <p><b>为什么不能只做 indexOf</b>:分块器会把连续空行、行尾空白归一化,
 * 子块文本与父块原文之间天然存在空白差异(尤其是带标题的文档,块之间原本是空行)。
 * 只做精确匹配会大面积为空偏移,导致"禁用子块裁剪"整体失效。这里做两级匹配:</p>
 * <ol>
 *   <li>快路径:整段精确匹配(绝大多数无标题文档走这里);</li>
 *   <li>慢路径:忽略全部空白后再匹配,并用下标映射还原到原文位置。</li>
 * </ol>
 *
 * @author ForgeAgent
 */
public final class ParentOffsets {

    /**
     * 未定位到
     */
    public static final int[] NOT_FOUND = {-1, -1};

    private ParentOffsets() {
    }

    /**
     * 定位子块
     *
     * @param parent     父块全文
     * @param sub        子块文本(可带「【章节路径】」前缀)
     * @param overlap    分块重叠字符数
     * @param cursorHint 上个子块在父块中的结束位置(首个传 0)
     * @return [start, end);未找到返回 {@link #NOT_FOUND}
     */
    public static int[] locate(String parent, String sub, int overlap, int cursorHint) {
        if (parent == null || parent.isEmpty() || sub == null || sub.isEmpty()) {
            return NOT_FOUND;
        }
        // 子块可能被分块器加了「【章节路径】」前缀,前缀是合成文本、不在父块里,先剥掉
        String probe = stripHeadingPrefix(sub);
        if (probe.isEmpty()) {
            return NOT_FOUND;
        }
        int from = Math.max(0, cursorHint - Math.max(overlap, 0));
        int start = parent.indexOf(probe, from);
        if (start < 0) {
            start = parent.indexOf(probe);
        }
        if (start >= 0 && start + probe.length() <= parent.length()) {
            return new int[]{start, start + probe.length()};
        }
        return locateIgnoringWhitespace(parent, probe, from);
    }

    /**
     * 去掉分块器加的「【章节路径】\n」前缀
     */
    static String stripHeadingPrefix(String sub) {
        if (sub == null) {
            return "";
        }
        if (!sub.startsWith("【")) {
            return sub;
        }
        int idx = sub.indexOf("】");
        if (idx <= 0) {
            return sub;
        }
        int after = idx + 1;
        if (after < sub.length() && sub.charAt(after) == '\n') {
            after++;
        }
        return sub.substring(after);
    }

    /**
     * 忽略空白地定位:归一化文本与原文位置一一映射,命中后还原为原文区间
     *
     * @param from 原文中的搜索起点(已扣除重叠)
     */
    private static int[] locateIgnoringWhitespace(String parent, String probe, int from) {
        int len = parent.length();
        int[] posMap = new int[len];
        StringBuilder normalized = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = parent.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            posMap[normalized.length()] = i;
            normalized.append(c);
        }
        String probeNorm = removeWhitespace(probe);
        if (probeNorm.isEmpty()) {
            return NOT_FOUND;
        }
        // 把原文起点换算成归一化下标,避免在前文重复片段上定位到上一次出现的位置
        int fromNorm = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (posMap[i] >= from) {
                fromNorm = i;
                break;
            }
        }
        int idx = normalized.indexOf(probeNorm, fromNorm);
        if (idx < 0) {
            idx = normalized.indexOf(probeNorm);
        }
        if (idx < 0) {
            return NOT_FOUND;
        }
        int start = posMap[idx];
        int end = posMap[idx + probeNorm.length() - 1] + 1;
        return new int[]{start, end};
    }

    private static String removeWhitespace(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
