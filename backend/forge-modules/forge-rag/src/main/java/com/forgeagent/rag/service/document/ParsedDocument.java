package com.forgeagent.rag.service.document;

import java.util.List;

/**
 * R5-5 解析产物:正文(含 {@code [[img:n]]} 图片占位标记) + 抽出的图片列表。
 *
 * <p>标记与图片同一次解析产出,序号天然对齐;描述由 {@code KbImageDescriber}
 * 按标记替换(开关关闭时标记被剥离,正文与未开启时完全一致)。</p>
 *
 * @param text   解析正文(可能含图片标记)
 * @param images 图片列表(可能为空)
 * @author ForgeAgent
 */
public record ParsedDocument(ParsedText text, List<DocumentImage> images) {

    public static ParsedDocument of(ParsedText text) {
        return new ParsedDocument(text, List.of());
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
}
