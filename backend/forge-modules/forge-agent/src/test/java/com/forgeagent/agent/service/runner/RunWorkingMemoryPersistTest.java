package com.forgeagent.agent.service.runner;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 08方案 / C4:L1 溢出沉淀的文本构建契约单测。
 *
 * <p>沉淀内容必须标注来源工具与指纹语义(方便回溯出处),超长截断且带截断提示,
 * 保证单条沉淀不撑爆 L3 注入预算;空文本返回空串由调用方跳过。</p>
 */
@Tag("dev")
class RunWorkingMemoryPersistTest {

    @Test
    void buildsContentWithToolPrefix() {
        String content = RunWorkingMemory.buildPersistContent("query_orders", "订单 1001 已发货", 500);
        assertTrue(content.startsWith("[query_orders 输出摘录] "), "必须标注来源工具");
        assertTrue(content.contains("订单 1001 已发货"));
    }

    @Test
    void nullToolFallsBackToUnknown() {
        String content = RunWorkingMemory.buildPersistContent(null, "文本", 500);
        assertTrue(content.startsWith("[unknown 输出摘录] "));
    }

    @Test
    void longTextIsTruncatedWithHint() {
        String text = "很长的输出".repeat(300);
        String content = RunWorkingMemory.buildPersistContent("tool", text, 100);
        assertTrue(content.length() <= 100, "沉淀内容必须受 maxChars 约束");
        assertTrue(content.contains("原文已截断"), "截断必须带提示,避免模型把片段当完整事实");
    }

    @Test
    void blankTextYieldsEmptyContent() {
        assertEquals("", RunWorkingMemory.buildPersistContent("tool", null, 500));
        assertEquals("", RunWorkingMemory.buildPersistContent("tool", "   ", 500));
    }

    @Test
    void tinyBudgetStillKeepsOneChar() {
        String content = RunWorkingMemory.buildPersistContent("t", "abc", 5);
        assertTrue(content.length() >= 1, "预算过小时至少保留 1 字符,不能产生空前缀");
    }
}
