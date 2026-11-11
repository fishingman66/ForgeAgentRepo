package com.forgeagent.agent.service.support;

import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.rag.service.document.TokenEstimator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L0 上下文预算分配器契约单测(P3 / M1)。
 *
 * <p>锁定四件事:空块时预算全部让渡给历史、高优先级块占用后历史额度下降、
 * 超预算块被裁剪且可下钻、静态提示词与动态检索块的先后顺序。</p>
 */
@Tag("dev")
class ContextBudgetAllocatorTest {

    private final ContextBudgetAllocator allocator = new ContextBudgetAllocator(new AgentChatProperties());

    private static ContextBudgetAllocator.Spec spec(String base, String summary, String memory,
                                                     String sql, String rag, String attachment) {
        return new ContextBudgetAllocator.Spec(1000, 100, base, summary, memory, sql, rag,
            attachment, "Q", "");
    }

    @Test
    void emptyBlocksYieldFullHistoryBudget() {
        ContextBudgetAllocator.Allocation a = allocator.allocate(
            spec("STATIC", "", "", "", "", ""));
        assertEquals(TokenEstimator.estimate("STATIC") + 1 + 64, a.usedTokens());
        assertTrue(a.historyBudget() > 512, "空动态块时历史应拿到让渡额度");
        assertTrue(a.trimmed().isEmpty());
        assertEquals("", a.retrievedContext());
    }

    @Test
    void occupiedRagReducesHistoryBudget() {
        String rag = "检索内容".repeat(40);
        ContextBudgetAllocator.Allocation empty = allocator.allocate(
            spec("STATIC", "", "", "", "", ""));
        ContextBudgetAllocator.Allocation filled = allocator.allocate(
            spec("STATIC", "", "", "", rag, ""));
        assertEquals(TokenEstimator.estimate(rag), filled.blockTokens().get("rag"));
        assertTrue(filled.historyBudget() < empty.historyBudget(),
            "RAG 占用后历史预算应下降");
        assertTrue(filled.retrievedContext().contains("检索内容"));
    }

    @Test
    void oversizedBlockIsTrimmedAndReported() {
        String huge = "知识片段".repeat(1000);
        ContextBudgetAllocator.Allocation a = allocator.allocate(
            spec("STATIC", "", "", "", huge, ""));
        assertTrue(a.trimmed().contains("rag"));
        assertTrue(a.blockTokens().get("rag") <= 1000,
            "裁剪后单块占用不得超过可用额度");
        assertTrue(a.retrievedContext().contains("已按上下文预算裁剪"));
        assertTrue(a.historyBudget() >= 512, "历史最低额度不得被挤掉");
    }

    @Test
    void staticPromptStaysBeforeDynamicBlocks() {
        ContextBudgetAllocator.Allocation a = allocator.allocate(
            spec("BASE", "SUMMARY", "MEMORY", "SCHEMA", "RAGTEXT", "FILETEXT"));
        String sys = a.systemPrompt();
        assertTrue(sys.indexOf("BASE") < sys.indexOf("SUMMARY"));
        assertTrue(sys.indexOf("SUMMARY") < sys.indexOf("MEMORY"));
        assertTrue(sys.indexOf("MEMORY") < sys.indexOf("SCHEMA"));
        assertTrue(a.retrievedContext().indexOf("FILETEXT") < a.retrievedContext().indexOf("RAGTEXT"),
            "附件块在前、知识检索块在后,与改造前顺序一致");
    }

    @Test
    void summaryAndMemoryHavePriorityOverRag() {
        String medium = "内容".repeat(50);   // 100 tokens,两个高优先级块都放得下
        String bigRag = "内容".repeat(100);  // 200 tokens,剩余额度不足以完整放下
        ContextBudgetAllocator.Allocation a = allocator.allocate(
            spec("BASE", medium, medium, "", bigRag, ""));
        assertEquals(TokenEstimator.estimate(medium), a.blockTokens().get("summary"));
        assertEquals(TokenEstimator.estimate(medium), a.blockTokens().get("memory"));
        assertTrue(a.blockTokens().getOrDefault("rag", 0) < TokenEstimator.estimate(bigRag),
            "额度不足时低优先级 RAG 先被裁剪");
        assertTrue(a.trimmed().contains("rag"));
    }
}
