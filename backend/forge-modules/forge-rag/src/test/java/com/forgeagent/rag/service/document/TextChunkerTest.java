package com.forgeagent.rag.service.document;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块器:字符口径保持存量行为,token 口径不超预算,结构策略真的保护表格/代码。
 *
 * <p>字符口径的断言写死了期望文本——这是**回归护栏**:存量库按 char 计价,
 * 任何改动只要动了切分逻辑,这里立刻会红。</p>
 */
@Tag("dev")
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @Test
    void charModeKeepsLegacyPacking() {
        String a = repeat('甲', 60);
        String b = repeat('乙', 60);
        String text = a + "\n" + b;
        List<String> chunks = chunker.chunk(text, 100, 20);
        assertEquals(2, chunks.size(), "两块各 60 字,预算 100 应切成 2 块");
        assertEquals(a, chunks.get(0), "第一块就是第一个段落");
        // 第二块 = 前块尾部 20 字 + 换行 + 第二段
        assertEquals(repeat('甲', 20) + "\n" + b, chunks.get(1));
    }

    @Test
    void charModeChunkNeverExceedsBudget() {
        String text = repeat('字', 1000);
        for (String c : chunker.chunk(text, 300, 50)) {
            assertTrue(c.length() <= 300, "块长度 " + c.length() + " 超过预算 300");
        }
    }

    @Test
    void tokenModeRespectsTokenBudget() {
        // 中文 1 字≈1 token:800 字按 200 token 切,应得到 4 块左右
        String text = repeat('知', 800);
        List<String> chunks = chunker.chunkByTokens(text, 200, 20);
        assertTrue(chunks.size() >= 4, "应至少切成 4 块,实际 " + chunks.size());
        for (String c : chunks) {
            assertTrue(TokenEstimator.estimate(c) <= 200,
                "块 token 数 " + TokenEstimator.estimate(c) + " 超过预算 200");
        }
    }

    @Test
    void tokenModeDiffersFromCharModeForLatinText() {
        // 英文 1 词≈1 token:同样的"500"预算,token 模式装得下更多字符
        String text = "word ".repeat(400).trim();
        int charChunks = chunker.chunk(text, 200, 20).size();
        int tokenChunks = chunker.chunkByTokens(text, 200, 20).size();
        assertTrue(tokenChunks < charChunks,
            "英文文本按 token 计价应切得更少:char=" + charChunks + " token=" + tokenChunks);
    }

    @Test
    void headingPathIsRecorded() {
        String md = "# 部署\n\n第一章正文内容。\n\n## 数据库\n\n第二章正文内容。\n";
        List<TextChunker.ChunkPiece> pieces = chunker.pieces(md, 500, 50, false, true);
        assertTrue(pieces.size() >= 2, "标题应开启新分块,实际 " + pieces.size());
        assertEquals("部署", pieces.get(0).headingPath());
        boolean hasNested = pieces.stream().anyMatch(p -> "部署 > 数据库".equals(p.headingPath()));
        assertTrue(hasNested, "二级标题应记录完整路径");
    }

    @Test
    void structureStrategyProtectsTables() {
        String table = "| 名称 | 说明 |\n|---|---|\n"
            + "| 甲 | " + repeat('一', 30) + " |\n"
            + "| 乙 | " + repeat('二', 30) + " |\n"
            + "| 丙 | " + repeat('三', 30) + " |\n";
        List<String> chunks = chunker.chunk(table, 80, 0);
        long headerRepeats = chunks.stream().filter(c -> c.contains("|---|")).count();
        assertTrue(headerRepeats >= 2,
            "表格拆分时每片都要重复表头,实际含表头的片数=" + headerRepeats + ",chunks=" + chunks);
    }

    @Test
    void fixedStrategyDoesNotRepeatTableHeader() {
        String table = "| 名称 | 说明 |\n|---|---|\n"
            + "| 甲 | " + repeat('一', 30) + " |\n"
            + "| 乙 | " + repeat('二', 30) + " |\n"
            + "| 丙 | " + repeat('三', 30) + " |\n";
        List<String> chunks = chunker.chunkFixed(table, 80, 0);
        long headerRepeats = chunks.stream().filter(c -> c.contains("|---|")).count();
        assertEquals(1, headerRepeats, "定长策略不做表头重复,实际=" + headerRepeats);
    }

    @Test
    void fixedStrategyIgnoresHeadingStructure() {
        String md = "# 标题\n\n正文";
        List<TextChunker.ChunkPiece> pieces = chunker.pieces(md, 500, 0, false, false);
        assertEquals(1, pieces.size());
        assertEquals(null, pieces.get(0).headingPath(), "定长策略不识别标题,不应产生章节路径");
        assertFalse(pieces.get(0).text().startsWith("【"), "定长策略不加章节前缀");
    }

    @Test
    void hardLimitCutsOversizedChunk() {
        String text = repeat('长', 9000);
        List<String> chunks = chunker.chunk(text, 20000, 0);
        assertTrue(chunks.size() >= 3, "9000 字应被硬上限切成多块,实际 " + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= TextChunker.HARD_CHAR_LIMIT,
                "块长度 " + c.length() + " 超过硬上限 " + TextChunker.HARD_CHAR_LIMIT);
        }
    }

    @Test
    void unitAndStrategyWhitelists() {
        assertTrue(TextChunker.isKnownUnit("char"));
        assertTrue(TextChunker.isKnownUnit("token"));
        assertFalse(TextChunker.isKnownUnit("word"));
        assertTrue(TextChunker.isKnownStrategy("structure"));
        assertTrue(TextChunker.isKnownStrategy("fixed"));
        assertFalse(TextChunker.isKnownStrategy("semantic"));
    }

    @Test
    void emptyInputGivesNoChunks() {
        assertTrue(chunker.pieces("", 500, 50, false, true).isEmpty());
        assertTrue(chunker.pieces("   ", 500, 50, false, true).isEmpty());
        assertTrue(chunker.pieces(null, 500, 50, false, true).isEmpty());
        assertTrue(chunker.pieces("abc", 0, 0, false, true).isEmpty());
    }
}
