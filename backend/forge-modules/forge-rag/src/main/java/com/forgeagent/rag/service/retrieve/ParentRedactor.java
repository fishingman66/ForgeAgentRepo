package com.forgeagent.rag.service.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 父块内容挖除器:把父块内「已被禁用的子块」换成占位符。
 *
 * <p><b>为什么需要</b>:父子分块下命中的是子块,注入的却是父块全文。若父块里混有已被运营
 * 禁用的子块,禁用内容会随父块一起进入模型上下文——这是合规问题,不只是排序问题。</p>
 *
 * <p><b>为什么 all-or-nothing</b>:任一段偏移失效(历史数据、重建换代)就整块保留原文,
 * 并让调用方按"整块不返回"处理。半吊子裁剪会产生"看起来完整、实际缺句"的段落,
 * 比直接不返回更危险。</p>
 *
 * @author ForgeAgent
 */
public final class ParentRedactor {

    /**
     * 被省略内容占位符
     */
    public static final String PLACEHOLDER = "（已省略被禁用内容）";

    private ParentRedactor() {
    }

    /**
     * 按偏移挖除禁用片段
     *
     * @param parent 父块全文
     * @param ranges 禁用区间 [[start, end), ...];允许乱序与重叠
     * @return 挖除后的文本;任一区间非法(越界/空区间)返回 null,表示调用方应保留原文
     */
    public static String redact(String parent, List<int[]> ranges) {
        if (parent == null || parent.isEmpty()) {
            return parent;
        }
        if (ranges == null || ranges.isEmpty()) {
            return parent;
        }
        List<int[]> valid = new ArrayList<>();
        for (int[] range : ranges) {
            if (range == null || range.length < 2) {
                return null;
            }
            int start = range[0];
            int end = range[1];
            if (start < 0 || end > parent.length() || start >= end) {
                return null;
            }
            valid.add(new int[]{start, end});
        }
        valid.sort(Comparator.comparingInt(r -> r[0]));
        // 合并重叠区间,避免同一段被替换两次
        List<int[]> merged = new ArrayList<>();
        for (int[] range : valid) {
            if (merged.isEmpty()) {
                merged.add(new int[]{range[0], range[1]});
                continue;
            }
            int[] last = merged.get(merged.size() - 1);
            if (range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        StringBuilder sb = new StringBuilder(parent.length());
        int cursor = 0;
        for (int[] range : merged) {
            sb.append(parent, cursor, range[0]).append(PLACEHOLDER);
            cursor = range[1];
        }
        sb.append(parent, cursor, parent.length());
        return sb.toString();
    }
}
