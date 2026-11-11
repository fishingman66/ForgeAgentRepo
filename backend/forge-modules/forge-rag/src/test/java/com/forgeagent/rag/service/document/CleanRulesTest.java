package com.forgeagent.rag.service.document;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清洗规则 JSON 解析:缺省键取默认,坏 JSON/非法正则回默认并留痕。
 */
@Tag("dev")
class CleanRulesTest {

    @Test
    void nullOrBlankGivesDefaults() {
        CleanRules d = CleanRules.parse(null, new ArrayList<>());
        assertEquals(CleanRules.defaults(), d);
        assertEquals(CleanRules.defaults(), CleanRules.parse("  ", new ArrayList<>()));
        assertTrue(CleanRules.defaults().headerFooter());
        assertTrue(CleanRules.defaults().blankNormalize());
        assertFalse(CleanRules.defaults().nfkc(), "NFKC 默认关:会把全角标点一并半角化");
        assertFalse(CleanRules.defaults().piiMask());
    }

    @Test
    void partialJsonMergesWithDefaults() {
        CleanRules r = CleanRules.parse("{\"piiMask\":true}", new ArrayList<>());
        assertTrue(r.piiMask());
        assertTrue(r.headerFooter(), "未指定的键取默认");
        assertTrue(r.blankNormalize());
        assertFalse(r.nfkc());
    }

    @Test
    void brokenJsonFallsBackAndReports() {
        List<String> errors = new ArrayList<>();
        CleanRules r = CleanRules.parse("{broken", errors);
        assertEquals(CleanRules.defaults(), r);
        assertEquals(1, errors.size());
    }

    @Test
    void nonObjectJsonFallsBackAndReports() {
        List<String> errors = new ArrayList<>();
        CleanRules r = CleanRules.parse("[1,2,3]", errors);
        assertEquals(CleanRules.defaults(), r);
        assertTrue(errors.get(0).contains("JSON 对象"));
    }

    @Test
    void invalidRegexEntriesAreSkippedWithReport() {
        List<String> errors = new ArrayList<>();
        CleanRules r = CleanRules.parse(
            "{\"regexReplace\":[{\"pattern\":\"[\"},{\"pattern\":\"敏感词\",\"replacement\":\"x\"}]}",
            errors);
        assertEquals(1, r.regexReplace().size(), "非法条目跳过,合法条目保留");
        assertEquals("敏感词", r.regexReplace().get(0).pattern());
        assertTrue(errors.get(0).contains("正则无效"));
    }

    @Test
    void nonTextPatternEntriesIgnored() {
        CleanRules r = CleanRules.parse(
            "{\"regexReplace\":[{\"pattern\":123},{\"replacement\":\"x\"}]}",
            new ArrayList<>());
        assertTrue(r.regexReplace().isEmpty(), "非字符串/缺 pattern 的条目忽略");
    }

    @Test
    void regexRuleReplacementDefaultsToEmpty() {
        CleanRules r = CleanRules.parse(
            "{\"regexReplace\":[{\"pattern\":\"水印\"}]}",
            new ArrayList<>());
        assertEquals("", r.regexReplace().get(0).replacement(), "缺 replacement 视为删除该内容");
    }
}
