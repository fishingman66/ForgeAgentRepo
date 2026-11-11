package com.forgeagent.rag.service.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 解析:文本页正常抽取并写页码标记;无文本层的页判为扫描页并计数。
 *
 * <p>用 PDFBox 现场生成测试文件,不依赖二进制 fixture(仓库里放 PDF 容易变成"看不清来源"的黑盒)。</p>
 */
@Tag("dev")
class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    private static byte[] textPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsTextWithPageMarker() throws Exception {
        ParsedText parsed = parser.parseRich(textPdf("Knowledge retrieval baseline"));
        assertEquals(1, parsed.pageCount());
        assertEquals(0, parsed.scannedPages());
        assertTrue(parsed.text().contains("[[page=1]]"), "应写入页码标记供引用定位");
        assertTrue(parsed.text().contains("Knowledge retrieval baseline"));
        assertTrue(parsed.warnings().isEmpty(), "正常文档不该有告警:" + parsed.warnings());
    }

    @Test
    void blankPageIsTreatedAsScanned() throws Exception {
        ParsedText parsed = parser.parseRich(blankPdf());
        assertEquals(1, parsed.pageCount());
        assertEquals(1, parsed.scannedPages());
        assertFalse(parsed.hasText(), "扫描页不产出正文");
        assertTrue(parsed.warningSummary().contains("OCR"), "要明确告诉调用方需要 OCR");
    }

    @Test
    void parseConvenienceReturnsSameText() throws Exception {
        byte[] pdf = textPdf("Hello");
        assertEquals(parser.parseRich(pdf).text(), parser.parse(pdf));
    }
}
