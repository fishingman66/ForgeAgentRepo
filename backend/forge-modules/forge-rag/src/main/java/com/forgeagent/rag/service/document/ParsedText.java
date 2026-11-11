package com.forgeagent.rag.service.document;

import java.util.List;

/**
 * 解析结果(不只是文本)。
 *
 * <p>只返回字符串时,上层无法区分"这篇是扫描件、一页文字都没提到"与"解析成功但内容为空";
 * 也无法知道"有 3 页因为是乱码被跳过了"。这些信息决定文档该标可用、标失败还是标需 OCR,
 * 所以必须随结果一起返回。</p>
 *
 * @param text          解析出的正文(已按页/工作表标记组织)
 * @param pageCount     识别到的页/工作表数量(无结构格式为 0)
 * @param scannedPages  判定为扫描页(文本层过少)而跳过的页数
 * @param garbledPages  判定为乱码而跳过的页数
 * @param warnings      解析告警(截断、跳过、格式降级等),用于落 notice 与日志
 * @author ForgeAgent
 */
public record ParsedText(String text, int pageCount, int scannedPages, int garbledPages,
                         List<String> warnings) {

    public ParsedText {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 纯文本场景:无分页信息、无告警
     */
    public static ParsedText of(String text) {
        return new ParsedText(text, 0, 0, 0, List.of());
    }

    /**
     * 是否有内容
     */
    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /**
     * 告警摘要(逗号连接,供落库展示)
     */
    public String warningSummary() {
        if (warnings.isEmpty()) {
            return "";
        }
        String joined = String.join("；", warnings);
        return joined.length() > 180 ? joined.substring(0, 180) + "…" : joined;
    }
}
