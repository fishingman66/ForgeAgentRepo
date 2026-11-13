package com.forgeagent.agent.service.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.common.core.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流运行快照的轨迹/变量编解码与展示裁剪。
 *
 * <p>从 {@code AgentWorkflowServiceImpl} 抽出的纯工具层:只依赖 Jackson,不碰数据库、
 * 登录态与执行引擎,因此可独立单测。语义与原实现完全一致:
 * vars 快照保留全文(审批恢复依赖它),仅 trace.output 展示字段允许裁剪。</p>
 */
@Slf4j
public final class WorkflowTraceCodec {

    /** 快照 JSON 软上限:超过后才做轨迹裁剪,常态零开销 */
    public static final int SNAPSHOT_JSON_SOFT_LIMIT = 256 * 1024;

    /** 单节点轨迹 output 上限(展示用,不参与执行) */
    private static final int NODE_OUTPUT_MAX_CHARS = 4000;

    private final ObjectMapper objectMapper;

    public WorkflowTraceCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 轨迹快照裁剪:超限时把每条 output 截到 4000 字符并加标记;
     * 只在 JSON 树上操作,不改动内存中的 trace 对象(事件流与本次返回结果仍为全文)。
     */
    public String compactTraceJson(List<WorkflowTrialRunVo.NodeTrace> trace)
        throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(trace);
        if (json.length() <= SNAPSHOT_JSON_SOFT_LIMIT) {
            return json;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode n : root) {
                    if (n instanceof ObjectNode obj && obj.has("output")) {
                        String out = obj.path("output").asText("");
                        if (out.length() > NODE_OUTPUT_MAX_CHARS) {
                            obj.put("output", out.substring(0, NODE_OUTPUT_MAX_CHARS)
                                + "\n...(轨迹输出过长已截断)");
                        }
                    }
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[工作流] 轨迹快照裁剪失败,保留原轨迹: {}", e.getMessage());
            return json;
        }
    }

    public List<WorkflowTrialRunVo.NodeTrace> readTrace(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, WorkflowTrialRunVo.NodeTrace.class));
        } catch (Exception e) {
            throw new ServiceException("运行轨迹快照解析失败:" + e.getMessage());
        }
    }

    public Map<String, String> readVars(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public Set<String> readSet(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(LinkedHashSet.class, String.class));
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    public static String truncateForTrace(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n...(过长已截断)";
    }
}
