package com.forgeagent.rag.service.document;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器。
 *
 * <p><b>结构感知</b>:Markdown 标题、表格、围栏代码块保持完整;按段落聚合,超出预算时优先在中文句读边界切分;
 * 块间保留重叠;每块携带所属章节路径(如 【部署 &gt; 数据库】),提升孤立分块的可解释性。</p>
 *
 * <p><b>两种计价单位</b>:</p>
 * <ul>
 *   <li>{@code char}:按字符数(存量知识库保持这个口径,行为不变);</li>
 *   <li>{@code token}:按 {@link TokenEstimator} 估算的 token 数。中文 1 字 ≈ 1 token,
 *       英文 1 词 ≈ 1 token,所以同样"500"的预算,中文与英文的实际长度差 3~4 倍——
 *       只按字符配置会让英文文档严重超预算、中文文档切得过碎。</li>
 * </ul>
 *
 * <p><b>两种策略</b>:{@code structure}(默认,表格/代码原子化 + 标题路径)与
 * {@code fixed}(只按段落/句读贪心装填,不做结构与表格保护),后者主要用于对照实验。</p>
 *
 * @author ForgeAgent
 */
@Component
public class TextChunker {

    /**
     * 结构策略(默认)
     */
    public static final String STRATEGY_STRUCTURE = "structure";

    /**
     * 定长策略:不做标题/表格/代码结构保护
     */
    public static final String STRATEGY_FIXED = "fixed";

    /**
     * 字符单位
     */
    public static final String UNIT_CHAR = "char";

    /**
     * token 单位
     */
    public static final String UNIT_TOKEN = "token";

    /**
     * 句子结束字符(硬切超长段落时优先在这些字符后断开,避免拦腰切句)
     */
    private static final String SENTENCE_ENDINGS = "。！？；!?\n.";

    /**
     * 单个分块的字符硬上限:即便按 token 计价,也不允许出现异常长的块
     * (token 估算误差或单行超长都可能把块撑大,后续拼上下文时会挤爆预算)
     */
    static final int HARD_CHAR_LIMIT = 4000;

    /**
     * 一个分块及其来源信息
     *
     * @param text        分块正文(可能带「【章节路径】」前缀)
     * @param headingPath 所属章节路径(如 `部署 > 数据库`),无标题时为 null
     */
    public record ChunkPiece(String text, String headingPath) {
    }

    // ---------------------------------------------------------------- 对外 API

    /**
     * 按字符数分块(存量口径,行为与历史版本一致)
     */
    public List<String> chunk(String text, int chunkSize, int overlap) {
        return texts(pieces(text, chunkSize, overlap, false, true));
    }

    /**
     * 按 token 数分块
     */
    public List<String> chunkByTokens(String text, int maxTokens, int overlapTokens) {
        return texts(pieces(text, maxTokens, overlapTokens, true, true));
    }

    /**
     * 按字符数分块,不做结构保护(对照实验用)
     */
    public List<String> chunkFixed(String text, int chunkSize, int overlap) {
        return texts(pieces(text, chunkSize, overlap, false, false));
    }

    /**
     * 按 token 数分块,不做结构保护
     */
    public List<String> chunkFixedByTokens(String text, int maxTokens, int overlapTokens) {
        return texts(pieces(text, maxTokens, overlapTokens, true, false));
    }

    /**
     * 统一入口:返回带章节路径的分块
     *
     * @param budget         预算(字符或 token,由 byToken 决定)
     * @param overlapBudget  重叠预算(同单位)
     * @param byToken        true=按 token 计价
     * @param structureAware true=表格/代码原子化 + 标题路径 + 标题开新块
     */
    public List<ChunkPiece> pieces(String text, int budget, int overlapBudget,
                                   boolean byToken, boolean structureAware) {
        if (text == null || text.isBlank() || budget <= 0) {
            return List.of();
        }
        Ctx ctx = new Ctx(byToken, structureAware, budget, Math.max(overlapBudget, 0));
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<ChunkPiece> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentPath = "";
        for (Block block : parseBlocks(normalized, structureAware)) {
            if (ctx.measure(block.text()) > budget) {
                // 超长块:先落袋当前块,再按块类型拆分(表格按行、代码按行、段落按句读边界)
                flush(chunks, current, currentPath);
                currentPath = "";
                splitLongBlock(ctx, block, chunks);
                continue;
            }
            if (block.heading() && current.length() > 0) {
                // 标题开启新分块,避免跨章节内容混杂
                flush(chunks, current, currentPath);
                currentPath = "";
            }
            if (ctx.measure(current.toString()) + ctx.measure(block.text()) + 1 > budget) {
                flush(chunks, current, currentPath);
                // 段落流之间携带前块尾部重叠;原子块(表格/代码)后不叠加重叠,碎片无检索意义
                String tail = block.atomic() || chunks.isEmpty() ? "" : tailOf(chunks.get(chunks.size() - 1).text(), ctx);
                current.append(tail);
                // 重叠尾段与新段落之间要补回分隔换行:否则这一块在原文里不是连续子串,
                // 父子分块的偏移定位必然失败(禁用子块裁剪会因此整块失效)
                if (!tail.isEmpty()) {
                    current.append('\n');
                }
                current.append(block.text());
            } else {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(block.text());
            }
            if (currentPath.isEmpty()) {
                currentPath = block.path();
            }
        }
        if (current.length() > 0) {
            flush(chunks, current, currentPath);
        }
        return enforceHardLimit(chunks);
    }

    /**
     * 硬保护:任何分块超过字符上限都再切一刀。
     *
     * <p>token 估算有误差,单行超长也可能把块撑大;一个 8000 字的块会直接吃掉后续检索的上下文预算,
     * 所以无论用哪种单位,最终都卡一道字符上限。</p>
     */
    private List<ChunkPiece> enforceHardLimit(List<ChunkPiece> pieces) {
        List<ChunkPiece> out = new ArrayList<>(pieces.size());
        for (ChunkPiece piece : pieces) {
            String t = piece.text();
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (t.length() <= HARD_CHAR_LIMIT) {
                out.add(piece);
                continue;
            }
            int start = 0;
            while (start < t.length()) {
                int end = Math.min(start + HARD_CHAR_LIMIT, t.length());
                out.add(new ChunkPiece(t.substring(start, end), piece.headingPath()));
                start = end;
            }
        }
        return out;
    }

    /**
     * 单位是否合法
     */
    public static boolean isKnownUnit(String unit) {
        return UNIT_CHAR.equals(unit) || UNIT_TOKEN.equals(unit);
    }

    /**
     * 策略是否合法
     */
    public static boolean isKnownStrategy(String strategy) {
        return STRATEGY_STRUCTURE.equals(strategy) || STRATEGY_FIXED.equals(strategy);
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 计价上下文:统一封装"按字符还是按 token"与预算
     */
    private record Ctx(boolean byToken, boolean structureAware, int budget, int overlap) {

        int measure(String s) {
            return byToken ? TokenEstimator.estimate(s) : (s == null ? 0 : s.length());
        }

        /**
         * 从 start 开始、预算内能取到的结束位置(不含);char 模式等价于 start+budget
         */
        int sliceEnd(String text, int start) {
            int len = text.length();
            if (start >= len) {
                return len;
            }
            if (!byToken) {
                return Math.min(start + budget, len);
            }
            // token 模式:二分找最长前缀使估算不超过预算
            int lo = start + 1;
            int hi = len;
            int best = start + 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (measure(text.substring(start, mid)) <= budget) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return Math.min(best, len);
        }

        /**
         * 取尾部重叠:不超过 overlap 预算的最长后缀
         */
        String tailOf(String chunk) {
            if (overlap <= 0 || chunk == null || chunk.isEmpty()) {
                return "";
            }
            if (measure(chunk) <= overlap) {
                return chunk;
            }
            if (!byToken) {
                return chunk.length() <= overlap ? chunk : chunk.substring(chunk.length() - overlap);
            }
            int lo = 1;
            int hi = chunk.length();
            int best = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (measure(chunk.substring(chunk.length() - mid)) <= overlap) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return best <= 0 ? "" : chunk.substring(chunk.length() - best);
        }
    }

    private static List<String> texts(List<ChunkPiece> pieces) {
        List<String> out = new ArrayList<>(pieces.size());
        for (ChunkPiece p : pieces) {
            out.add(p.text());
        }
        return out;
    }

    /**
     * 结构块解析:连续的表格行/围栏代码块合并为原子块,标题行与普通段落各自成块;
     * 维护标题层级栈,为每个块记录所属章节路径(如 "部署 > 数据库")
     *
     * @param structureAware false 时退化为"每行一个段落块",不做标题/表格/代码识别
     */
    private List<Block> parseBlocks(String text, boolean structureAware) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = text.split("\n");
        if (!structureAware) {
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    blocks.add(Block.para(trimmed, ""));
                }
            }
            return blocks;
        }
        List<String> headStack = new ArrayList<>();
        List<Integer> headLevels = new ArrayList<>();
        StringBuilder atomic = null;
        String atomicType = null;
        for (String line : lines) {
            String trimmed = line.trim();
            boolean fence = trimmed.startsWith("```");
            if ("code".equals(atomicType)) {
                atomic.append('\n').append(line);
                if (fence) {
                    blocks.add(Block.atomic(atomic.toString(), "code", pathOf(headStack)));
                    atomic = null;
                    atomicType = null;
                }
                continue;
            }
            if (fence) {
                atomic = new StringBuilder(line);
                atomicType = "code";
                continue;
            }
            boolean tableRow = trimmed.startsWith("|");
            if ("table".equals(atomicType)) {
                if (tableRow) {
                    atomic.append('\n').append(line);
                    continue;
                }
                blocks.add(Block.atomic(atomic.toString(), "table", pathOf(headStack)));
                atomic = null;
                atomicType = null;
            }
            if (tableRow) {
                atomic = new StringBuilder(line);
                atomicType = "table";
                continue;
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.matches("^#{1,6}\\s.*")) {
                int level = leadingHashes(trimmed);
                String title = trimmed.substring(level).replace('#', ' ').trim();
                // 弹出层级 >= 当前标题的旧标题,保持路径为真实祖先链
                while (!headLevels.isEmpty() && headLevels.get(headLevels.size() - 1) >= level) {
                    headLevels.remove(headLevels.size() - 1);
                    headStack.remove(headStack.size() - 1);
                }
                headStack.add(title);
                headLevels.add(level);
                blocks.add(Block.heading(trimmed, pathOf(headStack)));
                continue;
            }
            blocks.add(Block.para(trimmed, pathOf(headStack)));
        }
        if (atomic != null) {
            blocks.add(Block.atomic(atomic.toString(), atomicType, pathOf(headStack)));
        }
        return blocks;
    }

    private static int leadingHashes(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    private static String pathOf(List<String> headStack) {
        return String.join(" > ", headStack);
    }

    /**
     * 拆分超长块:表格按行拆分并保留表头,代码块按行窗口拆分,普通段落按句读边界拆分
     */
    private void splitLongBlock(Ctx ctx, Block block, List<ChunkPiece> chunks) {
        String prefix = block.path().isEmpty() ? "" : "【" + block.path() + "】\n";
        switch (block.type()) {
            case "table" -> splitTable(ctx, block.text(), chunks, prefix, block.path());
            case "code" -> splitByLines(ctx, block.text(), chunks, prefix, block.path());
            default -> splitLongParagraph(ctx, block.text(), chunks, prefix, block.path());
        }
    }

    /**
     * 表格按行拆分:表头(含 |---| 分隔行)在每个分片中重复,保持表格可读性
     */
    private void splitTable(Ctx ctx, String table, List<ChunkPiece> chunks, String prefix, String path) {
        String[] rows = table.split("\n");
        String header = rows[0];
        String separator = rows.length > 1 && rows[1].contains("---") ? rows[1] : null;
        int headLines = separator != null ? 2 : 1;
        StringBuilder current = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < rows.length; i++) {
            if (ctx.measure(rows[i]) > ctx.budget()) {
                // 极端情况:单行即超限,退化为按句读边界硬切
                if (current.length() > 0) {
                    chunks.add(new ChunkPiece(current.toString(), path));
                    current = new StringBuilder();
                }
                splitLongParagraph(ctx, rows[i], chunks, prefix, path);
                continue;
            }
            if (current.length() > 0
                && ctx.measure(current.toString()) + ctx.measure(rows[i]) + 1 > ctx.budget()) {
                chunks.add(new ChunkPiece(current.toString(), path));
                current = new StringBuilder();
                if (i >= headLines) {
                    current.append(header);
                    if (separator != null) {
                        current.append('\n').append(separator);
                    }
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(rows[i]);
        }
        if (current.length() > 0) {
            chunks.add(new ChunkPiece((first ? prefix : "") + current, path));
        }
    }

    /**
     * 代码块按行窗口拆分,保持行完整(单行长于预算时按字符硬切)
     */
    private void splitByLines(Ctx ctx, String text, List<ChunkPiece> chunks, String prefix, String path) {
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            if (ctx.measure(line) > ctx.budget()) {
                // 超长行先冲刷当前缓冲,再按字符硬切,避免整行逃逸分块大小约束
                if (current.length() > 0) {
                    chunks.add(new ChunkPiece(current.toString(), path));
                    current = new StringBuilder();
                }
                splitLongParagraph(ctx, line, chunks, prefix, path);
                continue;
            }
            if (current.length() > 0
                && ctx.measure(current.toString()) + ctx.measure(line) + 1 > ctx.budget()) {
                chunks.add(new ChunkPiece(current.toString(), path));
                current = new StringBuilder();
                first = false;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            chunks.add(new ChunkPiece((first ? prefix : "") + current, path));
        }
    }

    /**
     * 超长段落拆分:切点优先落在后半段的句子结束符后,块间保留重叠
     */
    private void splitLongParagraph(Ctx ctx, String p, List<ChunkPiece> chunks, String prefix, String path) {
        int start = 0;
        while (start < p.length()) {
            int end = ctx.sliceEnd(p, start);
            if (end < p.length() && !ctx.byToken()) {
                int cut = sentenceBoundary(p, start, end);
                if (cut > start) {
                    end = cut;
                }
            }
            chunks.add(new ChunkPiece(prefix + p.substring(start, end), path));
            if (end >= p.length()) {
                break;
            }
            int next = end - ctx.overlap();
            start = next > start ? next : end;
        }
    }

    /**
     * 在 [start, end) 的后半段内向前找句子结束符,返回其下一位置;找不到返回 -1
     */
    private int sentenceBoundary(String p, int start, int end) {
        int floor = start + (end - start) / 2;
        for (int i = end - 1; i >= floor; i--) {
            if (SENTENCE_ENDINGS.indexOf(p.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    private void flush(List<ChunkPiece> chunks, StringBuilder current, String path) {
        if (current.length() == 0) {
            return;
        }
        String text = current.toString();
        String withPrefix = path.isEmpty() ? text : "【" + path + "】\n" + text;
        chunks.add(new ChunkPiece(withPrefix, path.isEmpty() ? null : path));
        current.setLength(0);
    }

    private String tailOf(String chunk, Ctx ctx) {
        return ctx.tailOf(chunk);
    }

    /**
     * 结构块
     *
     * @param text    块文本
     * @param atomic  是否原子块(表格/代码,拆分与重叠策略不同)
     * @param heading 是否标题行(触发新分块)
     * @param type    块类型(para/heading/table/code)
     * @param path    所属章节路径(标题栈,如 "部署 > 数据库")
     */
    private record Block(String text, boolean atomic, boolean heading, String type, String path) {

        static Block para(String text, String path) {
            return new Block(text, false, false, "para", path);
        }

        static Block atomic(String text, String type, String path) {
            return new Block(text, true, false, type, path);
        }

        static Block heading(String text, String path) {
            return new Block(text, false, true, "heading", path);
        }
    }
}
