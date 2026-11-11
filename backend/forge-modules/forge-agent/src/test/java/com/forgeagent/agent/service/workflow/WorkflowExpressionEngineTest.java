package com.forgeagent.agent.service.workflow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流表达式引擎单测(质量基建批次)。
 *
 * <p>锁定两个最高价值纯函数的行为契约:变量插值的深取规则
 * 与条件求值的运算符优先级。此前这两个函数深埋在 2200 行的服务类里零覆盖。</p>
 */
@Tag("dev")
class WorkflowExpressionEngineTest {

    // ------------------------------------------------------------ interpolate

    @Test
    void interpolateFullKeyWins() {
        Map<String, String> vars = new HashMap<>();
        vars.put("start.query", "hello");
        // 完整键优先,不做前缀切分
        assertEquals("hello", WorkflowExpressionEngine.interpolate("{{start.query}}", vars));
    }

    @Test
    void interpolateDottedKeyPrefixSplit() {
        Map<String, String> vars = new HashMap<>();
        // 节点名.output 是含点键(完整值),剩余段 .items[0].name 对 JSON 深取
        vars.put("tool1.output", "{\"items\":[{\"name\":\"甲\"},{\"name\":\"乙\"}],\"total\":2}");
        assertEquals("甲", WorkflowExpressionEngine.interpolate("{{tool1.output.items[0].name}}", vars));
        assertEquals("2", WorkflowExpressionEngine.interpolate("{{tool1.output.total}}", vars));
        // 对象深取序列化为 JSON 文本
        assertEquals("{\"name\":\"甲\"}",
            WorkflowExpressionEngine.interpolate("{{tool1.output.items[0]}}", vars));
    }

    @Test
    void interpolateMissingKeepsPlaceholder() {
        Map<String, String> vars = new HashMap<>();
        vars.put("a.b", "1");
        // 深取路径不存在 → 占位符原样保留
        assertEquals("{{a.b.c.d}}", WorkflowExpressionEngine.interpolate("{{a.b.c.d}}", vars));
        // 完全未知变量同样保留
        assertEquals("{{unknown}}", WorkflowExpressionEngine.interpolate("{{unknown}}", vars));
    }

    @Test
    void interpolateMixedTextAndMultipleVars() {
        Map<String, String> vars = new HashMap<>();
        vars.put("start.name", "阿雁");
        vars.put("llm1.output", "结论A");
        assertEquals("用户:阿雁,结果:结论A",
            WorkflowExpressionEngine.interpolate("用户:{{start.name}},结果:{{llm1.output}}", vars));
    }

    @Test
    void interpolateNullOrEmptyTemplate() {
        Map<String, String> vars = Map.of();
        assertNull(WorkflowExpressionEngine.interpolate(null, vars));
        assertEquals("", WorkflowExpressionEngine.interpolate("", vars));
    }

    @Test
    void interpolateIndexOutOfBoundsKeepsPlaceholder() {
        Map<String, String> vars = new HashMap<>();
        vars.put("t.output", "[1,2]");
        assertEquals("{{t.output[5]}}", WorkflowExpressionEngine.interpolate("{{t.output[5]}}", vars));
    }

    // ------------------------------------------------------------ evalCondition

    @Test
    void conditionEqualityNumericAndString() {
        assertTrue(WorkflowExpressionEngine.evalCondition("3 == 3"));
        assertFalse(WorkflowExpressionEngine.evalCondition("3 == 4"));
        // 数字优先:数值比较,"03" 与 "3" 相等
        assertTrue(WorkflowExpressionEngine.evalCondition("03 == 3"));
        // 非数字回退字符串
        assertTrue(WorkflowExpressionEngine.evalCondition("abc == abc"));
        assertFalse(WorkflowExpressionEngine.evalCondition("abc == abd"));
    }

    @Test
    void conditionQuotesStripped() {
        assertTrue(WorkflowExpressionEngine.evalCondition("'deep' == deep"));
        assertTrue(WorkflowExpressionEngine.evalCondition("\"quick\" == quick"));
    }

    @Test
    void conditionContainsOperators() {
        assertTrue(WorkflowExpressionEngine.evalCondition("hello world contains world"));
        assertFalse(WorkflowExpressionEngine.evalCondition("hello world not contains world"));
        assertTrue(WorkflowExpressionEngine.evalCondition("hello world not contains xyz"));
        // 长词优先:先匹配 not contains 而不是 contains
        assertFalse(WorkflowExpressionEngine.evalCondition("abc not contains bc"));
    }

    @Test
    void conditionComparisonOperators() {
        assertTrue(WorkflowExpressionEngine.evalCondition("10 > 9"));
        assertFalse(WorkflowExpressionEngine.evalCondition("9 > 10"));
        assertTrue(WorkflowExpressionEngine.evalCondition("9 >= 9"));
        assertTrue(WorkflowExpressionEngine.evalCondition("9 <= 9"));
        assertFalse(WorkflowExpressionEngine.evalCondition("9 < 9"));
        assertTrue(WorkflowExpressionEngine.evalCondition("b > a"));
    }

    @Test
    void conditionUnsupportedSyntaxReturnsNull() {
        // 无运算符 → null(W6:调用方显式报错而非静默落默认分支)
        assertNull(WorkflowExpressionEngine.evalCondition("just a value"));
        assertNull(WorkflowExpressionEngine.evalCondition(""));
        // 运算符出现在开头(idx==0)不支持
        assertNull(WorkflowExpressionEngine.evalCondition("== x"));
    }
}
