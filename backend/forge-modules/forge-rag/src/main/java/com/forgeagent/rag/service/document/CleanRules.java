package com.forgeagent.rag.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 解析清洗规则(库级,存 `agent_knowledge.clean_rules` JSON)。
 *
 * <p>JSON 缺省的键取默认值;解析失败返回默认规则并把原因写进 errors,
 * 由调用方落 notice——坏配置不能阻断摄取,但也不能静默得没人知道。</p>
 *
 * <p>默认口径:{@code headerFooter=true}、{@code blankNormalize=true}、
 * {@code nfkc=false}、{@code piiMask=false}、无正则。
 * NFKC 默认关:它会把全角逗号/句号一并半角化,中文语料的标点会全变,慎开。</p>
 *
 * @param headerFooter  页眉页脚检测(按页标记,≥2 页才生效)
 * @param blankNormalize 空白归一(连续空行压一行、行尾空格、U+00A0;代码块内跳过)
 * @param nfkc          全半角归一(非代码块;慎用,见上)
 * @param piiMask       PII 脱敏(手机/身份证/银行卡/邮箱,保留前 3 后 4)
 * @param regexReplace  自定义正则替换(按顺序应用,非法条目跳过)
 * @author ForgeAgent
 */
public record CleanRules(boolean headerFooter,
                         boolean blankNormalize,
                         boolean nfkc,
                         boolean piiMask,
                         List<RegexRule> regexReplace) {

    /**
     * 单条自定义替换规则
     *
     * @param pattern     正则(java 语法)
     * @param replacement 替换文本(支持 $1 分组引用)
     */
    public record RegexRule(String pattern, String replacement) {
    }

    public CleanRules {
        regexReplace = regexReplace == null ? List.of() : List.copyOf(regexReplace);
    }

    public static CleanRules defaults() {
        return new CleanRules(true, true, false, false, List.of());
    }

    /**
     * 解析 JSON;NULL/空白按默认,坏 JSON 回默认并把原因追加到 errors
     */
    public static CleanRules parse(String json, List<String> errors) {
        if (json == null || json.isBlank()) {
            return defaults();
        }
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            if (node == null || !node.isObject()) {
                errors.add("clean_rules 不是 JSON 对象,已按默认规则处理");
                return defaults();
            }
            List<RegexRule> regex = new ArrayList<>();
            JsonNode arr = node.get("regexReplace");
            if (arr != null && arr.isArray()) {
                for (JsonNode item : arr) {
                    String pattern = textOrNull(item.get("pattern"));
                    String replacement = item.has("replacement") && item.get("replacement").isTextual()
                        ? item.get("replacement").asText() : "";
                    if (pattern == null || pattern.isBlank()) {
                        continue;
                    }
                    try {
                        Pattern.compile(pattern);
                        regex.add(new RegexRule(pattern, replacement));
                    } catch (PatternSyntaxException e) {
                        errors.add("clean_rules 正则无效已跳过:" + pattern);
                    }
                }
            }
            return new CleanRules(
                bool(node, "headerFooter", true),
                bool(node, "blankNormalize", true),
                bool(node, "nfkc", false),
                bool(node, "piiMask", false),
                regex);
        } catch (Exception e) {
            errors.add("clean_rules 解析失败已按默认规则处理:" + e.getMessage());
            return defaults();
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode v = node.get(field);
        return v == null || !v.isBoolean() ? fallback : v.asBoolean();
    }
}
