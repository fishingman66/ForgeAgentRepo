package com.forgeagent.rag.service.document;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayInputStream;
/**
 * Excel(xlsx)解析:按行输出 "单元格 | 单元格" 文本
 *
 * @author ForgeAgent
 */
public class XlsxDocumentParser implements DocumentParser {

    @Override
    public String extension() {
        return "xlsx";
    }

    @Override
    public String parse(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (workbook.getNumberOfSheets() > 0) {
                    sb.append("[[sheet=").append(sheet.getSheetName()).append("]]\n");
                }
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        String v = formatter.formatCellValue(row.getCell(c)).trim();
                        if (v.isEmpty()) {
                            continue;
                        }
                        if (line.length() > 0) {
                            line.append(" | ");
                        }
                        line.append(v);
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
