package com.forgeagent.rag.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R5-6 库级检索参数覆盖(agent_knowledge.retrieval_overrides JSON)。
 *
 * <p>覆盖键:{@code topK / searchMode / vectorWeight / keywordWeight / scoreThreshold / rerank}，
 * 空对象或坏 JSON 一律回退全局默认(不阻断检索)。<b>优先级</b>:调用方显式参数 > 库级覆盖 >
 * 全局配置;由于 {@code bo.topK} 有默认值 5,只在调用方"等于默认值"时才让库级覆盖生效,
 * 避免覆盖调用方意图。</p>
 *
 * @author ForgeAgent
 */
public record KnowledgeRetrievalOverrides(Integer topK, String searchMode, Double vectorWeight,
                                          Double keywordWeight, Double scoreThreshold, Boolean rerank) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final KnowledgeRetrievalOverrides EMPTY =
        new KnowledgeRetrievalOverrides(null, null, null, null, null, null);

    public static KnowledgeRetrievalOverrides parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return EMPTY;
            }
            return new KnowledgeRetrievalOverrides(
                intOrNull(root, "topK"),
                textOrNull(root, "searchMode"),
                doubleOrNull(root, "vectorWeight"),
                doubleOrNull(root, "keywordWeight"),
                doubleOrNull(root, "scoreThreshold"),
                boolOrNull(root, "rerank"));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * 校验(保存入口用):坏 JSON、非法取值直接拒绝,不让脏配置在检索期静默失效
     */
    public static void validate(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new com.forgeagent.common.core.exception.ServiceException("检索参数覆盖 JSON 不合法:" + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new com.forgeagent.common.core.exception.ServiceException("检索参数覆盖必须是 JSON 对象");
        }
        KnowledgeRetrievalOverrides ov = parse(json);
        if (ov.topK() != null && (ov.topK() < 1 || ov.topK() > 20)) {
            throw new com.forgeagent.common.core.exception.ServiceException("检索参数覆盖 topK 必须在 1~20");
        }
        if (ov.searchMode() != null && !java.util.List.of("hybrid", "vector", "keyword")
            .contains(ov.searchMode().toLowerCase())) {
            throw new com.forgeagent.common.core.exception.ServiceException("检索参数覆盖 searchMode 只支持 hybrid/vector/keyword");
        }
        if (ov.scoreThreshold() != null && (ov.scoreThreshold() < 0 || ov.scoreThreshold() > 1)) {
            throw new com.forgeagent.common.core.exception.ServiceException("检索参数覆盖 scoreThreshold 必须在 0~1");
        }
        if (ov.vectorWeight() != null && ov.vectorWeight() < 0) {
            throw new com.forgeagent.common.core.exception.ServiceException("vectorWeight 不能为负");
        }
        if (ov.keywordWeight() != null && ov.keywordWeight() < 0) {
            throw new com.forgeagent.common.core.exception.ServiceException("keywordWeight 不能为负");
        }
    }

    /**
     * 两路融合权重(默认 1.0;只作用于 RRF 贡献的相对大小)
     */
    public double vectorWeightOrDefault() {
        return vectorWeight == null ? 1.0 : vectorWeight;
    }

    public double keywordWeightOrDefault() {
        return keywordWeight == null ? 1.0 : keywordWeight;
    }

    /**
     * 缓存键签名:不同覆盖参数的结果不能互相命中
     */
    public String signature() {
        return String.valueOf(topK) + '|' + searchMode + '|' + vectorWeight + '|' + keywordWeight
            + '|' + scoreThreshold + '|' + rerank;
    }

    private static Integer intOrNull(JsonNode root, String field) {
        JsonNode v = root.get(field);
        return v != null && v.isNumber() ? v.asInt() : null;
    }

    private static Double doubleOrNull(JsonNode root, String field) {
        JsonNode v = root.get(field);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode v = root.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static Boolean boolOrNull(JsonNode root, String field) {
        JsonNode v = root.get(field);
        return v != null && v.isBoolean() ? v.asBoolean() : null;
    }
}
