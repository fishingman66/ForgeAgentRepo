package com.forgeagent.rag.service.document;

/**
 * 文档解析器:从文件字节中提取文本
 *
 * @author ForgeAgent
 */
public interface DocumentParser {

    /**
     * 支持的扩展名(小写,不含点)
     */
    String extension();

    /**
     * 解析文本
     */
    String parse(byte[] bytes) throws Exception;

    /**
     * 解析并返回带诊断信息的结果(页数、跳过的扫描页/乱码页、告警)。
     *
     * <p>默认实现只给文本,不提供分页诊断;能识别页边界的解析器(PDF/Excel)应覆盖本方法,
     * 否则上层无法区分"扫描件没提到字"与"解析成功但内容为空"。</p>
     */
    default ParsedText parseRich(byte[] bytes) throws Exception {
        return ParsedText.of(parse(bytes));
    }

    /**
     * R5-5:解析正文 + 图片(含位置标记)。默认不支持图片,等价于 {@link #parseRich}。
     * PDF/docx 覆盖本方法产出 {@code [[img:n]]} 标记与对应图片列表。
     */
    default ParsedDocument parseDocument(byte[] bytes) throws Exception {
        return ParsedDocument.of(parseRich(bytes));
    }
}
