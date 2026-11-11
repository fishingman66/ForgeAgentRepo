package com.forgeagent.rag.service.eval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * R3-2 证据命中判定(纯静态,确定性算法,不用 LLM,零业务依赖)。
 *
 * <p>核心口径:</p>
 * <ul>
 *   <li>normalize = NFKC + 去空白 + 去脱敏掩码(*) + 小写 —— 比较前两侧同口径归一</li>
 *   <li>overlapRatio(candidate, span) = min(1, 最长公共连续子串 / |span|)</li>
 *   <li>aggregateCoverage(candidates, span) = 各 candidate 在 span 内命中区间的并集 / |span|</li>
 *   <li>证据命中:coverage >= {@value #HIT_COVERAGE_THRESHOLD}(rank 前缀累积)</li>
 * </ul>
 *
 * <p>分母固定为 span(长父块不会因"带多了"被扣分);区间并集解决"一个 span
 * 横跨切分边界,两块拼起来才完整"被单块最大覆盖系统性低估的问题。</p>
 *
 * @author ForgeAgent
 */
public final class SpanHitJudge {

    /**
     * 证据判定阈值:rank 前缀累积覆盖 >= 0.5 即算命中该证据
     */
    public static final double HIT_COVERAGE_THRESHOLD = 0.5;

    /**
     * 最小命中区间长度(归一化后字符数):挡住噪声匹配。
     * 中文实词 2 字即有意义;纯拉丁字符的 2~3 字母组合(bigram/trigram)在长文本里
     * 到处出现,要求 >= 4 才计入。
     */
    public static final int MIN_MATCH_LEN = 2;

    /**
     * 纯拉丁(非 CJK)匹配的最小长度
     */
    public static final int MIN_MATCH_LEN_LATIN = 4;

    private SpanHitJudge() {
    }

    /**
     * 归一化:NFKC + 去全部空白 + 去脱敏掩码(*) + 小写
     */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char ch = nfkc.charAt(i);
            if (Character.isWhitespace(ch) || ch == '*') {
                continue;
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /**
     * 最长公共连续子串长度(滚动数组 DP,评测路径可接受 O(n*m))
     */
    public static int longestCommonLength(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                if (ca == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > best) {
                        best = cur[j];
                    }
                } else {
                    cur[j] = 0;
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
            Arrays.fill(cur, 0);
        }
        return best;
    }

    /**
     * 单块重叠率 = min(1, 最长公共连续子串 / |归一化 span|)
     */
    public static double overlapRatio(String candidate, String span) {
        String s = normalize(span);
        if (s.isEmpty()) {
            return 0.0;
        }
        int lcs = longestCommonLength(normalize(candidate), s);
        return Math.min(1.0, lcs / (double) s.length());
    }

    /**
     * candidate 在 span(归一化坐标系)内的全部命中区间 [start, end)。
     *
     * <p>实现:对 (candidate, span) 做 DP,公共子串沿对角线延伸,只在
     * "无法继续延伸"的对角线末端收集一次区间(即每条极大公共子串只收一次),
     * 长度不足 {@link #MIN_MATCH_LEN} 的丢弃。</p>
     */
    public static List<int[]> matchIntervals(String candidate, String span) {
        String c = normalize(candidate);
        String s = normalize(span);
        List<int[]> intervals = new ArrayList<>();
        if (c.isEmpty() || s.isEmpty()) {
            return intervals;
        }
        int[] prev = new int[s.length() + 1];
        int[] cur = new int[s.length() + 1];
        for (int i = 1; i <= c.length(); i++) {
            char cc = c.charAt(i - 1);
            for (int j = 1; j <= s.length(); j++) {
                if (cc != s.charAt(j - 1)) {
                    cur[j] = 0;
                    continue;
                }
                cur[j] = prev[j - 1] + 1;
                boolean extendable = i < c.length() && j < s.length() && c.charAt(i) == s.charAt(j);
                if (!extendable && cur[j] >= minMatchLen(s, j - cur[j], j)) {
                    intervals.add(new int[]{j - cur[j], j});
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
            Arrays.fill(cur, 0);
        }
        return intervals;
    }

    /**
     * 匹配区间的最小长度门槛:含 CJK 字符 ≥2,纯拉丁 ≥4
     */
    private static int minMatchLen(String s, int start, int end) {
        for (int i = start; i < end; i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                return MIN_MATCH_LEN;
            }
        }
        return MIN_MATCH_LEN_LATIN;
    }

    /**
     * 多块聚合覆盖:各块命中区间在 span 内的并集长度 / |归一化 span|。
     * span 为空(文档级用例)返回 0 —— 文档级只比 documentId,不走覆盖判定。
     */
    public static double coverage(List<String> candidates, String span) {
        String s = normalize(span);
        if (s.isEmpty()) {
            return 0.0;
        }
        boolean[] covered = new boolean[s.length()];
        boolean any = false;
        for (String cand : candidates) {
            if (cand == null || cand.isEmpty()) {
                continue;
            }
            for (int[] iv : matchIntervals(cand, span)) {
                any = true;
                Arrays.fill(covered, iv[0], iv[1], true);
            }
        }
        if (!any) {
            return 0.0;
        }
        int cnt = 0;
        for (boolean b : covered) {
            if (b) {
                cnt++;
            }
        }
        return cnt / (double) s.length();
    }

    /**
     * 单个 candidate 是否与任一 span 有重叠(>0),用于 Precision/NDCG 的"相关"判定
     */
    public static boolean overlapsAny(String candidate, List<String> spans) {
        for (String span : spans) {
            if (overlapRatio(candidate, span) > 0) {
                return true;
            }
        }
        return false;
    }
}
