package com.forgeagent.rag.service.document;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 正文抽取:脚本样式必须整段丢掉,标题要转成 Markdown,标签不能漏进分块。
 */
@Tag("dev")
class HtmlDocumentParserTest {

    private final HtmlDocumentParser parser = new HtmlDocumentParser();

    @Test
    void stripsScriptStyleAndComments() throws Exception {
        String html = """
            <html><head>
              <style>.a{color:red}</style>
              <script>var x = "<p>假的</p>";</script>
            </head><body>
              <!-- 注释 -->
              <p>真正的正文。</p>
            </body></html>
            """;
        String text = parser.parse(html.getBytes(StandardCharsets.UTF_8));
        assertTrue(text.contains("真正的正文"));
        assertFalse(text.contains("color:red"), "样式内容不该进正文");
        assertFalse(text.contains("var x"), "脚本内容不该进正文");
        assertFalse(text.contains("假的"), "脚本里的伪标签不该出现");
        assertFalse(text.contains("<"), "标签应全部清除");
    }

    @Test
    void headingsBecomeMarkdown() {
        String html = "<h1>总览</h1><p>段落一</p><h3>细节</h3><p>段落二</p>";
        String text = parser.extract(html);
        assertTrue(text.contains("# 总览"));
        assertTrue(text.contains("### 细节"));
        assertTrue(text.contains("段落一") && text.contains("段落二"));
    }

    @Test
    void titleBecomesTopHeading() {
        String html = "<html><head><title>产品手册</title></head><body><p>正文</p></body></html>";
        String text = parser.extract(html);
        assertTrue(text.startsWith("# 产品手册"), "标题应作为文首一级标题:" + text);
    }

    @Test
    void decodesCommonEntities() {
        assertEquals("a & b", HtmlDocumentParser.decodeEntities("a &amp; b"));
        assertEquals("\"引号\"", HtmlDocumentParser.decodeEntities("&quot;引号&quot;"));
        assertEquals("空格 与 破折号—", HtmlDocumentParser.decodeEntities("空格&nbsp;与&nbsp;破折号&mdash;"));
        assertEquals("A中", HtmlDocumentParser.decodeEntities("&#65;&#x4E2D;"));
        // 非法码点不炸
        assertTrue(HtmlDocumentParser.decodeEntities("&#99999999;") != null);
    }

    @Test
    void blockTagsBecomeLineBreaks() {
        String html = "<div>第一行</div><div>第二行</div><ul><li>条目</li></ul>";
        String text = parser.extract(html);
        String[] lines = text.split("\\R");
        assertTrue(lines.length >= 3, "块级标签应产生换行,实际:" + text);
    }

    @Test
    void htmExtensionIsSupported() {
        assertEquals("htm", new HtmlDocumentParser("htm").extension());
        assertEquals("html", parser.extension());
        assertTrue(DocumentParserFactory.supportedExtensions().contains("htm"));
        assertTrue(DocumentParserFactory.supportedExtensions().contains("html"));
    }

    @Test
    void emptyHtmlGivesEmptyText() {
        assertEquals("", parser.extract(""));
        assertEquals("", parser.extract("   "));
    }
}
