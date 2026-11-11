package com.forgeagent.rag.service.retrieve;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 父块禁用片段挖除:任一偏移失效即整块放弃(不做半吊子裁剪)。
 */
@Tag("dev")
class ParentRedactorTest {

    private static final String PARENT = "第一段内容。第二段内容。第三段内容。";

    @Test
    void noRangesKeepsTextUntouched() {
        assertEquals(PARENT, ParentRedactor.redact(PARENT, List.of()));
        assertEquals(PARENT, ParentRedactor.redact(PARENT, null));
        assertNull(ParentRedactor.redact(null, List.of(new int[]{0, 1})));
    }

    @Test
    void singleRangeReplacedByPlaceholder() {
        // 挖掉"第二段内容。"(索引 6..12)
        String result = ParentRedactor.redact(PARENT, List.of(new int[]{6, 12}));
        assertEquals("第一段内容。" + ParentRedactor.PLACEHOLDER + "第三段内容。", result);
    }

    @Test
    void unorderedRangesAreSorted() {
        String result = ParentRedactor.redact(PARENT, List.of(
            new int[]{12, 18}, new int[]{0, 6}));
        assertEquals(ParentRedactor.PLACEHOLDER + "第二段内容。" + ParentRedactor.PLACEHOLDER, result);
    }

    @Test
    void overlappingRangesMergedOnce() {
        String result = ParentRedactor.redact(PARENT, List.of(
            new int[]{0, 6}, new int[]{3, 12}));
        assertEquals(ParentRedactor.PLACEHOLDER + "第三段内容。", result);
    }

    @Test
    void invalidRangeGivesUpWholeBlock() {
        // 越界
        assertNull(ParentRedactor.redact(PARENT, List.of(new int[]{0, PARENT.length() + 1})));
        // 空区间
        assertNull(ParentRedactor.redact(PARENT, List.of(new int[]{5, 5})));
        // 负数
        assertNull(ParentRedactor.redact(PARENT, List.of(new int[]{-1, 5})));
        // 结构不完整
        assertNull(ParentRedactor.redact(PARENT, List.of(new int[]{3})));
    }

    @Test
    void validRangeWithOneInvalidAlsoGivesUp() {
        assertNull(ParentRedactor.redact(PARENT, List.of(new int[]{0, 6}, new int[]{10, 999})));
    }
}
