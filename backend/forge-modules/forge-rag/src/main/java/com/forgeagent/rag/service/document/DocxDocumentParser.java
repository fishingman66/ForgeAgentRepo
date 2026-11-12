package com.forgeagent.rag.service.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
/**
 * Word(docx)解析:段落 + 表格单元格文本
 *
 * @author ForgeAgent
 */
public class DocxDocumentParser implements DocumentParser {

    @Override
    public String extension() {
        return "docx";
    }

    @Override
    public String parse(byte[] bytes) throws Exception {
        return parseDocument(bytes).text().text();
    }

    /**
     * R5-5:段落文本 + 段落锚点处的内嵌图片(docx 无页概念,位置用段落序号)。
     * 图片以 {@code [[img:n]]} 标记插在该段文本之后,保证描述与上下文相邻。
     */
    @Override
    public ParsedDocument parseDocument(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        List<DocumentImage> images = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            for (int pi = 0; pi < paragraphs.size(); pi++) {
                XWPFParagraph p = paragraphs.get(pi);
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append('\n');
                }
                for (org.apache.poi.xwpf.usermodel.XWPFRun run : p.getRuns()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFPicture picture : run.getEmbeddedPictures()) {
                        if (images.size() >= DocumentImage.MAX_IMAGES) {
                            if (!warnings.contains(DocxDocumentParser.IMAGE_CAP_WARNING)) {
                                warnings.add(DocxDocumentParser.IMAGE_CAP_WARNING);
                            }
                            break;
                        }
                        org.apache.poi.xwpf.usermodel.XWPFPictureData data = picture.getPictureData();
                        if (data == null || data.getData() == null || data.getData().length == 0) {
                            continue;
                        }
                        if (!seenHashes.add(PdfDocumentParser.hashOf(data.getData()))) {
                            continue;
                        }
                        int[] size = pixelSize(picture);
                        if (size[0] < DocumentImage.MIN_PIXELS || size[1] < DocumentImage.MIN_PIXELS) {
                            continue;
                        }
                        int index = images.size();
                        images.add(new DocumentImage(index, null, pi, mediaTypeOf(data),
                            data.getData(), size[0], size[1]));
                        sb.append("[[img=").append(index).append("]]\n");
                    }
                }
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (line.length() > 0) {
                            line.append(" | ");
                        }
                        line.append(cell.getText().trim());
                    }
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
        return new ParsedDocument(ParsedText.of(sb.toString()), images);
    }

    static final String IMAGE_CAP_WARNING = "图片超过上限 " + DocumentImage.MAX_IMAGES + " 张,仅处理前 "
        + DocumentImage.MAX_IMAGES + " 张";

    /**
     * 图片显示尺寸(像素):EMU ÷ 9525;元数据缺失时按最小值处理(宁可描述,不误删真图)
     */
    private static int[] pixelSize(org.apache.poi.xwpf.usermodel.XWPFPicture picture) {
        try {
            var ext = picture.getCTPicture().getSpPr().getXfrm().getExt();
            int width = (int) (ext.getCx() / 9525L);
            int height = (int) (ext.getCy() / 9525L);
            if (width <= 0 || height <= 0) {
                return new int[]{DocumentImage.MIN_PIXELS, DocumentImage.MIN_PIXELS};
            }
            return new int[]{width, height};
        } catch (Exception e) {
            return new int[]{DocumentImage.MIN_PIXELS, DocumentImage.MIN_PIXELS};
        }
    }

    private static String mediaTypeOf(org.apache.poi.xwpf.usermodel.XWPFPictureData data) {
        String ext = data.suggestFileExtension();
        if (ext == null) {
            return "image/png";
        }
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            default -> "image/png";
        };
    }
}
