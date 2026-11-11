package com.forgeagent.rag.service.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 摄取侧解析:页码/工作表标记。
 *
 * <p>这里只 new 出对象调辅助方法,依赖全部为 null——不碰 Spring。</p>
 */
@Tag("dev")
class KnowledgeDocProcessorImplTest {

    private final KnowledgeDocProcessorImpl processor =
        new KnowledgeDocProcessorImpl(null, null, null, null, null, null, null);

    @Test
    void pageNoParsedFromPageMarkOnly() {
        assertEquals(3, processor.extractPageNo("[[page=3]]\n正文"));
        assertNull(processor.extractPageNo("[[sheet=销售表]]\n正文"));
        assertNull(processor.extractPageNo("[[page=abc]]"));
        assertNull(processor.extractPageNo("没有标记的正文"));
        assertNull(processor.extractPageNo(null));
    }

    @Test
    void sheetNameParsedAndTruncated() {
        assertEquals("销售表", processor.extractSheetName("[[sheet=销售表]]\n| a | b |"));
        assertNull(processor.extractSheetName("[[page=2]]"));
        assertNull(processor.extractSheetName(null));
        String longName = "表".repeat(150);
        String parsed = processor.extractSheetName("[[sheet=" + longName + "]]");
        assertEquals(100, parsed.length(), "超长工作表名截断到 100,避免超列宽");
    }
}
