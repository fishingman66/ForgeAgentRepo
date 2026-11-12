package com.forgeagent.rag.service.document;

/**
 * R5-5 解析阶段抽出的图片(PDF 按页、docx 按段落锚点)。
 *
 * <p>只带"能被检索的入口"所需信息:序号(与正文 {@code [[img:n]]} 标记对应)、位置(页码/段落)、
 * 媒体类型与原始字节。像素尺寸用于过滤图标/水印噪声。</p>
 *
 * @author ForgeAgent
 */
public record DocumentImage(int index, Integer pageNo, Integer paragraphIndex,
                            String mediaType, byte[] data, int width, int height) {

    /**
     * 最小边长(像素):小于此值的多为图标/分隔线/水印,描述它们只会污染检索
     */
    public static final int MIN_PIXELS = 200;

    /**
     * 单文档最多抽取的图片数(VLM 成本闸门:1 张 1 次调用)
     */
    public static final int MAX_IMAGES = 20;

    /**
     * 位置描述(供描述文本前缀与告警使用)
     */
    public String locationLabel() {
        if (pageNo != null) {
            return "p" + pageNo;
        }
        return paragraphIndex == null ? "" : "段" + (paragraphIndex + 1);
    }

    /**
     * 是否值得送 VLM(尺寸与字节均达标)
     */
    public boolean describable(int minPixels) {
        return data != null && data.length > 0 && width >= minPixels && height >= minPixels;
    }
}
