package com.forgeagent.rag.service.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5-3 查询分词单测:CJK 滑窗、字母数字 run、单字过滤
 *
 * @author ForgeAgent
 */
@Tag("dev")
class GraphQueryTokenizerTest {

    @Test
    void cjkSegmentsGetWindowed() {
        List<String> tokens = GraphQueryTokenizer.tokenize("部署手册怎么用");
        assertTrue(tokens.contains("部署手册怎么用"), "整段保留");
        assertTrue(tokens.contains("部署"), "2 字滑窗");
        assertTrue(tokens.contains("部署手册"), "4 字滑窗");
        assertTrue(tokens.contains("手册"), "");
    }

    @Test
    void alnumRunsKeptWhole() {
        List<String> tokens = GraphQueryTokenizer.tokenize("ProductX1 的 v2 版本");
        assertTrue(tokens.contains("ProductX1"));
        assertTrue(tokens.contains("v2"));
        assertTrue(tokens.contains("版本"));
    }

    @Test
    void singleCharsDropped() {
        List<String> tokens = GraphQueryTokenizer.tokenize("a 1 的");
        assertEquals(List.of(), tokens);
    }

    @Test
    void blankQueryReturnsEmpty() {
        assertEquals(List.of(), GraphQueryTokenizer.tokenize(null));
        assertEquals(List.of(), GraphQueryTokenizer.tokenize("  "));
    }
}
