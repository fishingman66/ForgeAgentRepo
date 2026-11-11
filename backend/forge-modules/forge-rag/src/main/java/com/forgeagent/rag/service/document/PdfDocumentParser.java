package com.forgeagent.rag.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析(Apache PDFBox)
 *
 * <p>逐页抽取并写入 {@code [[page=N]]} 标记,供检索引用定位页码。三条防护:</p>
 * <ul>
 *   <li><b>扫描页</b>:一页提取不到 20 个字符就判为扫描页(没有文本层),跳过并计数,
 *       让上层标"需 OCR"而不是产出一堆空块;</li>
 *   <li><b>乱码页</b>:字体映射损坏时会抽出"字形汤",按可识别字符占比判定后跳过;</li>
 *   <li><b>规模</b>:页数上限 2000、正文上限 2000 万字符,超出即截断并告警——</li>
 * </ul>
 *
 * @author ForgeAgent
 */
public class PdfDocumentParser implements DocumentParser {

    /**
     * 单页文本少于此长度视为扫描页(无文本层)
     */
    static final int SCANNED_PAGE_TEXT_THRESHOLD = 20;

    /**
     * 页数上限
     */
    static final int MAX_PAGES = 2000;

    /**
     * 正文长度上限(字符)
     */
    static final int MAX_CHARS = 20_000_000;

    @Override
    public String extension() {
        return "pdf";
    }

    @Override
    public String parse(byte[] bytes) throws Exception {
        return parseRich(bytes).text();
    }

    @Override
    public ParsedText parseRich(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int total = doc.getNumberOfPages();
            int limit = Math.min(total, MAX_PAGES);
            List<String> warnings = new ArrayList<>();
            if (total > MAX_PAGES) {
                warnings.add("页数超过上限 " + MAX_PAGES + ",仅解析前 " + MAX_PAGES + " 页");
            }
            StringBuilder sb = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            int scanned = 0;
            int garbled = 0;
            boolean truncated = false;
            for (int page = 1; page <= limit; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text;
                try {
                    text = stripper.getText(doc);
                } catch (Exception e) {
                    // 单页抽取失败不影响整篇(坏页常见于嵌入了异常字体)
                    warnings.add("第 " + page + " 页抽取失败已跳过");
                    continue;
                }
                String stripped = text == null ? "" : text.strip();
                if (stripped.length() < SCANNED_PAGE_TEXT_THRESHOLD) {
                    scanned++;
                    continue;
                }
                if (GarbledTextDetector.isGarbled(stripped)) {
                    garbled++;
                    continue;
                }
                if (sb.length() + stripped.length() > MAX_CHARS) {
                    truncated = true;
                    break;
                }
                sb.append("[[page=").append(page).append("]]\n").append(stripped).append('\n');
            }
            if (scanned > 0) {
                warnings.add(scanned + " 页无文本层(扫描件),需 OCR 才能检索");
            }
            if (garbled > 0) {
                warnings.add(garbled + " 页文本乱码,已跳过");
            }
            if (truncated) {
                warnings.add("正文超过 " + MAX_CHARS + " 字符,已截断");
            }
            return new ParsedText(sb.toString(), total, scanned, garbled, warnings);
        }
    }
}
