package com.forgeagent.rag.service.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import java.io.ByteArrayInputStream;
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
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append('\n');
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
        return sb.toString();
    }
}
