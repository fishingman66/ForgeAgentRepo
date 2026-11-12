package com.forgeagent.rag.service.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5-5 PDF 抽图单测:大图抽出并带页号标记、小图标被过滤
 *
 * @author ForgeAgent
 */
@Tag("dev")
class PdfImageExtractionTest {

    private byte[] pdfWithImage(int imageWidth, int imageHeight) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            BufferedImage bi = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < imageWidth; x++) {
                for (int y = 0; y < imageHeight; y++) {
                    bi.setRGB(x, y, (x * 31 + y * 17) % 0xFFFFFF);
                }
            }
            PDImageXObject img = LosslessFactory.createFromImage(doc, bi);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("deployment guide for capacity planning");
                cs.endText();
                cs.drawImage(img, 50, 400, 120, 120);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void largeImageExtractedWithMarker() throws Exception {
        ParsedDocument parsed = new PdfDocumentParser().parseDocument(pdfWithImage(300, 300));
        assertEquals(1, parsed.images().size(), "300x300 应被抽出");
        DocumentImage image = parsed.images().get(0);
        assertEquals(1, image.pageNo());
        assertEquals("p1", image.locationLabel());
        assertTrue(parsed.text().text().contains("[[img=0]]"), parsed.text().text());
        assertTrue(image.describable(DocumentImage.MIN_PIXELS));
    }

    @Test
    void tinyIconFiltered() throws Exception {
        ParsedDocument parsed = new PdfDocumentParser().parseDocument(pdfWithImage(60, 60));
        assertTrue(parsed.images().isEmpty(), "小于 200px 的图标应被过滤");
        assertTrue(!parsed.text().text().contains("[[img="), parsed.text().text());
    }

    @Test
    void parseRichStillReturnsTextWithoutBreakingCallers() throws Exception {
        ParsedText text = new PdfDocumentParser().parseRich(pdfWithImage(300, 300));
        assertTrue(text.text().contains("capacity planning"));
    }
}
