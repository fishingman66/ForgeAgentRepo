package com.forgeagent.rag.service.document;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 乱码判定:正常中英文放行,PDF 字体映射损坏后的"字形汤"必须被识别。
 */
@Tag("dev")
class GarbledTextDetectorTest {

    @Test
    void normalChineseIsNotGarbled() {
        String text = "这是一段正常的中文正文，包含标点、数字 2026 与英文 word。";
        assertFalse(GarbledTextDetector.isGarbled(text));
        assertEquals(1.0d, GarbledTextDetector.recognizableRatio(text), 1e-9);
    }

    @Test
    void garbledGlyphSoupIsDetected() {
        // 典型的 ToUnicode 损坏输出:大量私有区/替换字符
        String soup = "\uE000\uE001\uE002\uE003\uE004\uE005\uE006\uE007 ab";
        assertTrue(GarbledTextDetector.isGarbled(soup));
        assertTrue(GarbledTextDetector.recognizableRatio(soup) < 0.5d);
    }

    @Test
    void halfGarbledIsJudgedByRatio() {
        // 可识别 6 个、不可识别 4 个 → 0.6 > 0.5,按"勉强可读"放行
        String text = "正常文字六个\uE000\uE001\uE002\uE003";
        assertFalse(GarbledTextDetector.isGarbled(text));
        // 可识别 2 个、不可识别 4 个 → 0.33,判乱码
        String mostlyBad = "正常\uE000\uE001\uE002\uE003";
        assertTrue(GarbledTextDetector.isGarbled(mostlyBad));
    }

    @Test
    void emptyTextIsNotGarbled() {
        assertFalse(GarbledTextDetector.isGarbled(""));
        assertFalse(GarbledTextDetector.isGarbled("   \n\t "));
        assertFalse(GarbledTextDetector.isGarbled(null));
        assertEquals(1.0d, GarbledTextDetector.recognizableRatio(null), 1e-9);
    }

    @Test
    void commonSymbolsAndFullWidthCountAsReadable() {
        String text = "《标题》【备注】—— （全角括号） ① ● ★ 50%";
        assertFalse(GarbledTextDetector.isGarbled(text),
            "中文文档里常见的符号不应被算成乱码;实际占比="
                + GarbledTextDetector.recognizableRatio(text));
    }
}
