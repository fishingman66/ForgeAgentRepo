package com.forgeagent.rag.service.document;

/**
 * 乱码页检测。
 *
 * <p><b>为什么需要</b>:PDF 的字体映射(ToUnicode)损坏时,PDFBox 抽出来的是一串"字形汤",
 * 不是空白也不是报错,而是看起来有字的乱码。这类文本入库后会白占向量空间、污染相似度分布,
 * 并且永远不可能被正常问题命中——比直接失败更糟,因为没人会发现。</p>
 *
 * <p>判定方式:统计"可识别字符"(ASCII 可见字符、中日常用区、中英标点、全半角)在非空白字符中的占比,
 * 低于 {@link #GARBLED_RATIO_THRESHOLD} 视为乱码。只采样前 {@link #SAMPLE_LENGTH} 个字符,
 * 长文档不必全量扫。</p>
 *
 * @author ForgeAgent
 */
public final class GarbledTextDetector {

    /**
     * 可识别字符占比低于该值判为乱码
     */
    public static final double GARBLED_RATIO_THRESHOLD = 0.5d;

    /**
     * 采样长度
     */
    public static final int SAMPLE_LENGTH = 4000;

    private GarbledTextDetector() {
    }

    /**
     * 文本是否乱码(空白文本不算乱码,交给"是否为空"的判断)
     */
    public static boolean isGarbled(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int limit = Math.min(text.length(), SAMPLE_LENGTH);
        int meaningful = 0;
        int recognizable = 0;
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            meaningful++;
            if (isRecognizable(c)) {
                recognizable++;
            }
        }
        if (meaningful == 0) {
            return false;
        }
        // 用整数比较避免浮点误差:recognizable / meaningful < 0.5
        return recognizable * 2 < meaningful;
    }

    /**
     * 可识别字符占比(0~1,便于日志与阈值调优)
     */
    public static double recognizableRatio(String text) {
        if (text == null || text.isBlank()) {
            return 1d;
        }
        int limit = Math.min(text.length(), SAMPLE_LENGTH);
        int meaningful = 0;
        int recognizable = 0;
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            meaningful++;
            if (isRecognizable(c)) {
                recognizable++;
            }
        }
        return meaningful == 0 ? 1d : (double) recognizable / meaningful;
    }

    /**
     * 字符是否落在常见可读区段
     */
    static boolean isRecognizable(char c) {
        if (c >= 0x20 && c <= 0x7E) {
            return true;   // ASCII 可见
        }
        if (c >= 0xA0 && c <= 0xBF) {
            return true;   // Latin-1 补充/倒置标点
        }
        if (c >= 0x2010 && c <= 0x2027) {
            return true;   // 常用标点:破折号、引号、省略号
        }
        if (c >= 0x3000 && c <= 0x303F) {
            return true;   // CJK 标点
        }
        if (c >= 0x2100 && c <= 0x218F) {
            return true;   // 字母式符号 / 数字形式(№ ① Ⅰ)
        }
        if (c >= 0x2460 && c <= 0x24FF) {
            return true;   // 带圈字母数字
        }
        if (c >= 0x25A0 && c <= 0x26FF) {
            return true;   // 几何图形 / 杂项符号(● ★)
        }
        if (c >= 0x3040 && c <= 0x30FF) {
            return true;   // 日文假名
        }
        if (c >= 0x3400 && c <= 0x4DBF) {
            return true;   // CJK 扩展 A
        }
        if (c >= 0x4E00 && c <= 0x9FFF) {
            return true;   // CJK 基本区
        }
        if (c >= 0xAC00 && c <= 0xD7AF) {
            return true;   // 韩文
        }
        if (c >= 0xFF00 && c <= 0xFFEF) {
            return true;   // 全角/半角形式
        }
        return false;
    }
}
