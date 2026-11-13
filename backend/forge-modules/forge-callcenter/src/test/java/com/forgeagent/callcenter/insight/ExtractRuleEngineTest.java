package com.forgeagent.callcenter.insight;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C4:信息提取规则的命中口径(只从脱敏文本抽,未知字段跳过)。 */
@Tag("dev")
class ExtractRuleEngineTest {

    @Test
    void extractsDeclaredKeysOnly() {
        String text = "订单号 AB123456 已经三天了,金额 128.5 元,怎么还不退";
        List<ExtractRuleEngine.Hit> hits = ExtractRuleEngine.extract(text, List.of("orderNo", "amount", "unknownKey"));
        assertEquals(2, hits.size(), "未知字段不产出条目");
        assertEquals("orderNo", hits.get(0).key());
        assertEquals("AB123456", hits.get(0).value());
        assertEquals("amount", hits.get(1).key());
        assertEquals("128.5", hits.get(1).value());
    }

    @Test
    void explicitEmptyDeclarationExtractsNothing() {
        assertTrue(ExtractRuleEngine.extract("订单号 AB123456", List.of()).isEmpty());
    }

    @Test
    void blankTextYieldsNothing() {
        assertTrue(ExtractRuleEngine.extract(null, List.of("orderNo")).isEmpty());
        assertTrue(ExtractRuleEngine.extract("   ", List.of("orderNo")).isEmpty());
    }

    @Test
    void unMatchedKeyIsSkippedSilently() {
        // 声明了 name 但文本里没有自报家门,只产出命中项
        assertTrue(ExtractRuleEngine.extract("我要退货", List.of("name")).isEmpty());
    }

    @Test
    void phoneKeepsMaskedForm() {
        // 转写入库前已 PII 脱敏,抽取只能拿到掩码形态——这本身就是合规要求
        List<ExtractRuleEngine.Hit> hits = ExtractRuleEngine.extract("我的号码 138****1234", List.of("phone"));
        assertEquals(1, hits.size());
        assertEquals("138****1234", hits.get(0).value());
    }

    @Test
    void knownKeysCoverDeclaredDefaults() {
        List<String> keys = ExtractRuleEngine.knownKeys();
        assertTrue(keys.containsAll(List.of("orderNo", "amount", "phone", "name", "address")));
    }
}
