package com.forgeagent.rag.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测用例证据标注(R3-1):{@code [{"documentId":123,"span":"原文片段"}]}。
 *
 * <p>span 为空 = 文档级证据(只比 documentId,精确相等不做模糊包含);
 * span 非空 = span 级证据(走 {@link SpanHitJudge} 覆盖判定)。</p>
 *
 * @author ForgeAgent
 */
public record EvalEvidence(Long documentId, String span) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析证据 JSON;空串/null 返回空列表(用例走 legacy 口径)。
     * 结构非法直接抛异常 —— 保存入口已校验,运行期遇到说明数据被绕过写入,宁可失败不可静默。
     */
    public static List<EvalEvidence> parse(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ServiceException("证据标注 JSON 不合法:" + e.getMessage());
        }
        if (!root.isArray()) {
            throw new ServiceException("证据标注 JSON 必须是数组");
        }
        List<EvalEvidence> list = new ArrayList<>();
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                throw new ServiceException("证据标注数组元素必须是对象");
            }
            JsonNode idNode = node.get("documentId");
            if (idNode == null || !idNode.canConvertToLong()) {
                throw new ServiceException("证据标注缺少 documentId");
            }
            String span = node.hasNonNull("span") ? node.get("span").asText() : null;
            list.add(new EvalEvidence(idNode.asLong(), StringUtils.isBlank(span) ? null : span));
        }
        return list;
    }

    /**
     * 保存前校验(不合法直接抛 ServiceException)
     */
    public static void validate(String json) {
        if (StringUtils.isBlank(json)) {
            return;
        }
        List<EvalEvidence> list = parse(json);
        if (list.isEmpty()) {
            throw new ServiceException("证据标注不能是空数组,请删除该字段或填写证据");
        }
        for (EvalEvidence e : list) {
            if (e.documentId() == null || e.documentId() <= 0) {
                throw new ServiceException("证据标注的 documentId 必须是有效文档ID");
            }
        }
    }

    /**
     * 是否包含对指定文档的引用
     */
    public boolean refersDocument(Long documentId) {
        return documentId != null && documentId.equals(this.documentId());
    }
}
