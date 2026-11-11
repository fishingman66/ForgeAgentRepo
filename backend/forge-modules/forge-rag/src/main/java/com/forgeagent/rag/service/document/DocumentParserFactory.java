package com.forgeagent.rag.service.document;

import com.forgeagent.common.core.exception.ServiceException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
/**
 * 文档解析器工厂(支持 txt/md/csv/json/html/htm/docx/xlsx/pdf)
 *
 * <p>扩展名 → 解析器一一对应。`html/htm` 走专用解析器抽正文(此前直接按纯文本读,
 * 分块里会混入大量标签),其余纯文本格式继续用直读。</p>
 *
 * @author ForgeAgent
 */
public class DocumentParserFactory {

    private static final Map<String, DocumentParser> PARSERS = new HashMap<>();

    static {
        register(new TxtDocumentParser("txt"));
        register(new TxtDocumentParser("md"));
        register(new TxtDocumentParser("csv"));
        register(new TxtDocumentParser("json"));
        register(new TxtDocumentParser("sql"));
        register(new HtmlDocumentParser());
        register(new HtmlDocumentParser("htm"));
        register(new DocxDocumentParser());
        register(new XlsxDocumentParser());
        register(new PdfDocumentParser());
    }

    private static void register(DocumentParser parser) {
        PARSERS.put(parser.extension(), parser);
    }

    public static DocumentParser get(String ext) {
        DocumentParser parser = PARSERS.get(ext == null ? "" : ext.toLowerCase());
        if (parser == null) {
            throw new ServiceException("不支持的文档格式:" + ext + ",支持 " + String.join("/", PARSERS.keySet()));
        }
        return parser;
    }

    public static Set<String> supportedExtensions() {
        return PARSERS.keySet();
    }
}
