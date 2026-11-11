package com.forgeagent.rag.service.document;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析清洗管线(R2-2)。顺序钉死,顺序错了会互相打架:
 *
 * <pre>
 * 页眉页脚/页码 → NFKC → 正则替换 → PII 脱敏 → 空白归一
 * </pre>
 *
 * <p><b>为什么 NFKC 在正则之前</b>:用户写的替换正则一般按半角字符描述,
 * 先归一再替换,规则才稳定。NFKC 默认关——它会把全角逗号/句号一并半角化,
 * 中文语料的标点会全变,只在确认需要的库上显式开启。</p>
 *
 * <p><b>页眉页脚</b>:按 {@code [[page=N]]} / {@code [[sheet=x]]} 标记切页,
 * 只看每页前 2 个与后 2 个非空行,行长 ≤ 120;某归一化行(去掉全部数字+折叠空白)
 * 出现在 ≥ ceil(页数 × 0.6) 个页即判定,从所有页删除。页数不足 2 不启用——
 * 单页文本的"重复行"就是正文本身,没有统计意义。纯页码行(短且只含数字/符号)单独删除。</p>
 *
 * <p><b>代码块保护</b>:``` 围栏内的行跳过 NFKC/正则/空白归一,避免破坏缩进与全角语义;
 * PII 脱敏对代码块同样生效(敏感信息不因是代码就豁免)。无页标记的文本(txt/md/html)
 * 视为单页,页眉页脚检测自然不启用,其余清洗照常。</p>
 *
 * @author ForgeAgent
 */
public final class DocumentCleaner {

    /**
     * 页标记行:[[page=3]] / [[sheet=销售表]] 独占一行
     */
    private static final Pattern MARKER_LINE = Pattern.compile("^\\[\\[(page|sheet)=[^\\]]+\\]\\]$");

    /**
     * 页眉页脚候选行最大长度
     */
    static final int HEADER_FOOTER_MAX_LEN = 120;

    /**
     * 页码行最大长度(超过说明是正文)
     */
    static final int PAGE_NO_MAX_LEN = 5;

    /**
     * 每页首/尾取多少个非空行做候选
     */
    static final int CANDIDATE_LINES_PER_SIDE = 2;

    /**
     * 清洗结果
     *
     * @param text             清洗后的全文(页标记保留)
     * @param warnings         告警(非法正则跳过等)
     * @param removedHeader    删除的页眉页脚行数
     * @param removedPageNo    删除的纯页码行数
     * @param regexLines       发生正则替换的行数
     * @param piiLines         发生 PII 打码的行数
     */
    public record CleanResult(String text, List<String> warnings,
                              int removedHeader, int removedPageNo,
                              int regexLines, int piiLines) {

        public boolean changed() {
            return removedHeader > 0 || removedPageNo > 0 || regexLines > 0 || piiLines > 0;
        }
    }

    private DocumentCleaner() {
    }

    public static CleanResult clean(String text, CleanRules rules) {
        if (text == null || text.isBlank() || rules == null) {
            return new CleanResult(text == null ? "" : text, List.of(), 0, 0, 0, 0);
        }
        List<String> warnings = new ArrayList<>();
        List<PageBlock> pages = splitPages(text);

        int removedHeader = 0;
        int removedPageNo = 0;
        if (rules.headerFooter()) {
            int[] removed = stripHeaderFooterAndPageNo(pages);
            removedHeader = removed[0];
            removedPageNo = removed[1];
        }

        // 预编译正则:非法条目跳过并告警,不让坏配置炸掉摄取
        List<Pattern> patterns = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        for (CleanRules.RegexRule rule : rules.regexReplace()) {
            try {
                patterns.add(Pattern.compile(rule.pattern()));
                replacements.add(rule.replacement() == null ? "" : rule.replacement());
            } catch (Exception e) {
                warnings.add("clean_rules 正则无效已跳过:" + rule.pattern());
            }
        }

        int regexLines = 0;
        int piiLines = 0;
        boolean inCode = false;
        List<String> out = new ArrayList<>();
        for (PageBlock page : pages) {
            if (page.marker() != null) {
                out.add(page.marker());
            }
            for (String line : page.lines()) {
                if (isFence(line)) {
                    inCode = !inCode;
                    out.add(line);
                    continue;
                }
                String work = line;
                if (!inCode) {
                    if (rules.nfkc()) {
                        work = Normalizer.normalize(work, Normalizer.Form.NFKC);
                    }
                    for (int i = 0; i < patterns.size(); i++) {
                        Matcher m = patterns.get(i).matcher(work);
                        String replaced = m.replaceAll(Matcher.quoteReplacement(replacements.get(i)));
                        if (!replaced.equals(work)) {
                            regexLines++;
                            work = replaced;
                        }
                    }
                }
                if (rules.piiMask()) {
                    String masked = KbPiiMasker.mask(work);
                    if (!masked.equals(work)) {
                        piiLines++;
                        work = masked;
                    }
                }
                if (rules.blankNormalize() && !inCode) {
                    work = work.replace('\u00A0', ' ');
                    work = work.stripTrailing();
                }
                out.add(work);
            }
        }

        String result = rules.blankNormalize() ? collapseBlankLines(out) : String.join("\n", out);
        return new CleanResult(result, warnings, removedHeader, removedPageNo, regexLines, piiLines);
    }

    private static boolean isFence(String line) {
        return line.strip().startsWith("```");
    }

    /**
     * 按页标记切分;无标记文本归入单个无标记页
     */
    private static List<PageBlock> splitPages(String text) {
        List<PageBlock> pages = new ArrayList<>();
        PageBlock current = new PageBlock(null, new ArrayList<>());
        for (String line : text.split("\n", -1)) {
            if (MARKER_LINE.matcher(line.strip()).matches()) {
                if (!(current.marker() == null && current.lines().isEmpty())) {
                    pages.add(current);
                }
                current = new PageBlock(line.strip(), new ArrayList<>());
                continue;
            }
            current.lines().add(line);
        }
        pages.add(current);
        return pages;
    }

    /**
     * 页眉页脚检测 + 纯页码行删除
     *
     * @return [页眉页脚行数, 页码行数]
     */
    private static int[] stripHeaderFooterAndPageNo(List<PageBlock> pages) {
        // 只有带标记的页参与统计:无标记前言只有一段,没有"重复出现"的语义
        List<PageBlock> marked = pages.stream().filter(p -> p.marker() != null).toList();
        if (marked.size() < 2) {
            return new int[]{0, 0};
        }
        int threshold = (int) Math.ceil(marked.size() * 0.6);

        // 归一化行 -> 出现过的页(去重)
        Map<String, Set<Integer>> seen = new HashMap<>();
        List<List<Integer>> candidateIdx = new ArrayList<>(marked.size());
        for (int p = 0; p < marked.size(); p++) {
            List<Integer> candidates = candidateIndexes(marked.get(p).lines());
            candidateIdx.add(candidates);
            for (Integer idx : candidates) {
                String norm = normalizeForMatch(marked.get(p).lines().get(idx));
                if (!norm.isEmpty()) {
                    seen.computeIfAbsent(norm, k -> new HashSet<>()).add(p);
                }
            }
        }
        Set<String> hits = new HashSet<>();
        seen.forEach((norm, pageSet) -> {
            if (pageSet.size() >= threshold) {
                hits.add(norm);
            }
        });

        int removedHeader = 0;
        int removedPageNo = 0;
        for (int p = 0; p < marked.size(); p++) {
            List<String> lines = marked.get(p).lines();
            Set<Integer> drop = new HashSet<>();
            for (Integer idx : candidateIdx.get(p)) {
                String line = lines.get(idx).strip();
                if (!hits.isEmpty() && hits.contains(normalizeForMatch(line))) {
                    drop.add(idx);
                    removedHeader++;
                } else if (isPageNoLine(line)) {
                    drop.add(idx);
                    removedPageNo++;
                }
            }
            if (!drop.isEmpty()) {
                List<String> kept = new ArrayList<>(lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    if (!drop.contains(i)) {
                        kept.add(lines.get(i));
                    }
                }
                marked.get(p).lines().clear();
                marked.get(p).lines().addAll(kept);
            }
        }
        return new int[]{removedHeader, removedPageNo};
    }

    /**
     * 每页前/后各 2 个非空行的索引(升序)
     */
    private static List<Integer> candidateIndexes(List<String> lines) {
        List<Integer> nonBlank = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                nonBlank.add(i);
            }
        }
        List<Integer> candidates = new ArrayList<>();
        int head = Math.min(CANDIDATE_LINES_PER_SIDE, nonBlank.size());
        for (int i = 0; i < head; i++) {
            candidates.add(nonBlank.get(i));
        }
        int tailStart = Math.max(head, nonBlank.size() - CANDIDATE_LINES_PER_SIDE);
        for (int i = tailStart; i < nonBlank.size(); i++) {
            if (!candidates.contains(nonBlank.get(i))) {
                candidates.add(nonBlank.get(i));
            }
        }
        return candidates;
    }

    /**
     * 页眉页脚匹配用的归一化:去全部数字(含全角)+折叠空白+小写
     */
    private static String normalizeForMatch(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean lastSpace = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            sb.append(Character.toLowerCase(c));
            lastSpace = false;
        }
        return sb.toString().strip();
    }

    /**
     * 纯页码行:短、至少含一个数字、不含任何字母(汉字/英文都算)
     */
    private static boolean isPageNoLine(String line) {
        String s = line.strip();
        if (s.isEmpty() || s.length() > PAGE_NO_MAX_LEN) {
            return false;
        }
        boolean hasDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (Character.isLetter(c)) {
                return false;
            }
        }
        return hasDigit;
    }

    /**
     * 连续空行折叠为一行(代码块内不动);去掉全文首尾多余空行
     */
    private static String collapseBlankLines(List<String> lines) {
        // 重建行与代码块状态的对应关系:标记行与内容行按原顺序已在 out 中
        StringBuilder sb = new StringBuilder();
        boolean inCode = false;
        boolean prevBlank = false;
        boolean first = true;
        for (String line : lines) {
            if (MARKER_LINE.matcher(line.strip()).matches()) {
                if (!first) {
                    sb.append('\n');
                }
                sb.append(line);
                prevBlank = false;
                first = false;
                continue;
            }
            if (isFence(line)) {
                inCode = !inCode;
            }
            boolean blank = line.isBlank();
            if (blank && !inCode) {
                if (prevBlank || first) {
                    continue;
                }
                prevBlank = true;
                sb.append('\n');
                continue;
            }
            if (!first) {
                sb.append('\n');
            }
            sb.append(line);
            prevBlank = false;
            first = false;
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 页块(内部结构)
     */
    private static final class PageBlock {
        private final String marker;
        private final List<String> lines;

        private PageBlock(String marker, List<String> lines) {
            this.marker = marker;
            this.lines = lines;
        }

        private String marker() {
            return marker;
        }

        private List<String> lines() {
            return lines;
        }
    }
}
