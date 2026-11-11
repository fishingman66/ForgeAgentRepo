package com.forgeagent.rag.service.document;

import java.nio.charset.StandardCharsets;
/**
 * 纯文本 / Markdown 解析
 *
 * @author ForgeAgent
 */
public class TxtDocumentParser implements DocumentParser {

    private final String extension;

    public TxtDocumentParser(String extension) {
        this.extension = extension;
    }

    @Override
    public String extension() {
        return extension;
    }

    @Override
    public String parse(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
