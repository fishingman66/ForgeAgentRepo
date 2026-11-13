package com.forgeagent.callcenter.insight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 信息提取规则引擎(C4):按渠道声明的 {@code extract_keys} 做声明式抽取。
 *
 * <p>设计取舍:字段清单由渠道配置决定(不同业务线关心的槽位不同),
 * 但抽取规则本身内置——正则规则可审计、可离线复现,不需要模型;
 * 模型抽取(槽位理解)留后续实现,沿用同一 {@code item_key} 口径。</p>
 *
 * <p><b>只从脱敏文本抽取</b>:入参 text 已是 {@code CsPiiMasker} 之后的文本,
 * 手机号一类字段抽出的是掩码形态(138****1234),洞察表不引入新的明文来源。</p>
 */
public final class ExtractRuleEngine {

    /** 字段名 → 规则。顺序固定便于单测与文档对齐。 */
    private static final Map<String, Rule> RULES = rules();

    private ExtractRuleEngine() {
    }

    /** 一条抽取规则:分组号从 1 开始,取第一个非空分组。 */
    public record Rule(String key, Pattern pattern) {
    }

    /** 命中结果:字段名 + 值(已 trim)+ 命中的规则名。 */
    public record Hit(String key, String value) {
    }

    /** 已内置的字段清单(渠道声明了未知字段时按未命中跳过)。 */
    public static List<String> knownKeys() {
        return List.copyOf(RULES.keySet());
    }

    /**
     * 按声明字段抽取;未声明的字段与未命中的字段都不产出条目。
     * 空文本或空字段清单直接返回空列表。
     */
    public static List<Hit> extract(String text, List<String> keys) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isBlank() || keys == null || keys.isEmpty()) {
            return hits;
        }
        for (String key : keys) {
            Rule rule = RULES.get(key);
            if (rule == null) {
                continue;
            }
            Matcher matcher = rule.pattern().matcher(text);
            if (matcher.find()) {
                String value = matcher.group(1);
                if (value != null && !value.isBlank()) {
                    hits.add(new Hit(key, value.trim()));
                }
            }
        }
        return hits;
    }

    private static Map<String, Rule> rules() {
        Map<String, Rule> map = new LinkedHashMap<>();
        // 订单号:订单/单号 + 6~32 位字母数字(允许连字符)
        map.put("orderNo", new Rule("orderNo",
            Pattern.compile("(?:订单号|订单编号|单号)[:：\\s]*([A-Za-z0-9\\-]{6,32})")));
        // 金额:数字 + 元/块;只取第一个金额
        map.put("amount", new Rule("amount",
            Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块钱|块)")));
        // 手机号:优先取已脱敏形态,其次 11 位明文(正常链路不会出现,防御性保留)
        map.put("phone", new Rule("phone",
            Pattern.compile("(\\d{3}\\*{2,}\\d{4}|1[3-9]\\d{9})")));
        // 姓名:自报家门句式
        map.put("name", new Rule("name",
            Pattern.compile("(?:我叫|姓名是|我是)\\s*([\\u4e00-\\u9fa5]{2,4})")));
        // 地址:地址/住在 + 中文数字串
        map.put("address", new Rule("address",
            Pattern.compile("(?:地址是|地址|住在)\\s*([\\u4e00-\\u9fa5A-Za-z0-9\\-号栋单元室]{4,40})")));
        // 保持声明顺序(Map.copyOf 不保序,knownKeys 需要确定性)
        return Collections.unmodifiableMap(map);
    }
}
