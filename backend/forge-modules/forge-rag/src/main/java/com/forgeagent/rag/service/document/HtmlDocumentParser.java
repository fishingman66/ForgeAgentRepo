package com.forgeagent.rag.service.document;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 解析:抽正文、丢噪声、保留标题层级。
 *
 * <p><b>为什么单独写</b>:此前 `html` 直接走纯文本解析器,分块里会留下大量 `<div class="...">` 标签,
 * 既污染向量、又几乎不可能被语义命中。这里用正则做"够用就好"的抽取:</p>
 * <ul>
 *   <li>整段删除 script/style/noscript/template(含内容),它们不是正文;</li>
 *   <li>h1~h6 转 Markdown 标题,让分块器能按标题切段并生成章节路径;</li>
 *   <li>块级标签转成换行,行内标签直接去掉;</li>
 *   <li>解码常见实体(&amp;nbsp; &amp;amp; &amp;lt; &amp;gt; &amp;quot; &amp;#39; &amp;#NNN;);</li>
 *   <li><b>零出站请求</b>:不下载任何外部资源,img/script 的 src 只当噪声丢弃(不引入 SSRF 面)。</li>
 * </ul>
 *
 * <p>不引入 jsoup 是为了不新增依赖(离线仓库无此包);对知识库文档这类常规 HTML 足够,
 * 复杂 DOM 场景(需要 CSS 选择器)以后再做。</p>
 *
 * @author ForgeAgent
 */
public class HtmlDocumentParser implements DocumentParser {

    /**
     * 噪声标签(连同内容整段删除)
     */
    private static final Pattern NOISE_BLOCK = Pattern.compile(
        "<(script|style|noscript|template|iframe|object)\\b[^>]*>.*?</\\1\\s*>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern TITLE = Pattern.compile(
        "<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern HEADING = Pattern.compile(
        "<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 块级标签:转成换行,避免整页挤成一行
     */
    private static final Pattern BLOCK_TAG = Pattern.compile(
        "</?(p|div|section|article|header|footer|li|ul|ol|tr|table|thead|tbody|blockquote|pre|br|hr|dd|dt|dl)\\b[^>]*>",
        Pattern.CASE_INSENSITIVE);

    /**
     * 剩余标签
     */
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");

    private final String ext;

    public HtmlDocumentParser() {
        this("html");
    }

    /**
     * @param ext 注册用的扩展名(htm 与 html 用同一套抽取逻辑)
     */
    public HtmlDocumentParser(String ext) {
        this.ext = ext;
    }

    @Override
    public String extension() {
        return ext;
    }

    @Override
    public String parse(byte[] bytes) throws Exception {
        return parseRich(bytes).text();
    }

    @Override
    public ParsedText parseRich(byte[] bytes) throws Exception {
        String html = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String text = extract(html);
        return new ParsedText(text, 0, 0, 0,
            text.isBlank() ? java.util.List.of("HTML 未提取到正文") : java.util.List.of());
    }

    /**
     * 抽取正文(纯函数,便于单测)
     */
    static String extract(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String work = COMMENT.matcher(html).replaceAll(" ");
        work = NOISE_BLOCK.matcher(work).replaceAll(" ");

        // 标题先降级成 Markdown,顺序要在"删标签"之前,才能拿到内部纯文本
        Matcher title = TITLE.matcher(work);
        StringBuilder head = new StringBuilder();
        if (title.find()) {
            String t = stripInline(title.group(1));
            if (!t.isBlank()) {
                head.append("# ").append(t).append("\n\n");
            }
        }
        work = title.replaceAll(" ");

        Matcher h = HEADING.matcher(work);
        StringBuffer withHeadings = new StringBuffer(work.length());
        while (h.find()) {
            int level = Integer.parseInt(h.group(1));
            String inner = stripInline(h.group(2));
            String replacement = inner.isBlank() ? "" : "\n" + "#".repeat(level) + " " + inner + "\n";
            h.appendReplacement(withHeadings, Matcher.quoteReplacement(replacement));
        }
        h.appendTail(withHeadings);
        work = withHeadings.toString();

        work = BLOCK_TAG.matcher(work).replaceAll("\n");
        work = ANY_TAG.matcher(work).replaceAll("");
        work = decodeEntities(work);

        // 逐行去首尾空白,丢掉空行碎片
        StringBuilder out = new StringBuilder(head);
        for (String line : work.split("\\R")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                out.append(t).append('\n');
            }
        }
        return MULTI_BLANK.matcher(out.toString()).replaceAll("\n\n").strip();
    }

    /**
     * 行内标签直接去掉(用于标题内部)
     */
    private static String stripInline(String inner) {
        String t = ANY_TAG.matcher(inner).replaceAll("");
        return decodeEntities(t).strip();
    }

    /**
     * 常见实体解码(不追求完整 HTML5 实体表)
     */
    static String decodeEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        String s = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&middot;", "·");
        return decodeNumericEntities(s);
    }

    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9A-Fa-f]{1,6});");

    private static String decodeNumericEntities(String text) {
        Matcher m = NUMERIC.matcher(text);
        StringBuffer sb = new StringBuffer(text.length());
        while (m.find()) {
            int code;
            try {
                code = m.group(1).isEmpty() ? Integer.parseInt(m.group(2))
                    : Integer.parseInt(m.group(2), 16);
            } catch (NumberFormatException e) {
                continue;
            }
            if (code <= 0 || code > 0x10FFFF) {
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 扩展名归一(htm 与 html 同处理)
     */
    static String normalizeExt(String ext) {
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}
