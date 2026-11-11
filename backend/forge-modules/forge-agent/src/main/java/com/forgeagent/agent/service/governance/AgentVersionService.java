package com.forgeagent.agent.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 配置版本快照服务(P1 / B1)。
 *
 * <p>发布时冻结完整配置 JSON 与 SHA-256 摘要;版本只追加、不覆盖,
 * 回滚也是生成新版本而不是抹掉历史。快照明细可逐字段 diff,供发布前核对。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
public class AgentVersionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public AgentVersionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 从实体抽取稳定有序的配置快照 */
    public Map<String, Object> buildSnapshot(AgentInfo agent) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", agent.getName());
        snapshot.put("description", agent.getDescription());
        snapshot.put("systemPrompt", agent.getSystemPrompt());
        snapshot.put("welcomeMsg", agent.getWelcomeMsg());
        snapshot.put("suggestedQuestions", agent.getSuggestedQuestions());
        snapshot.put("modelId", agent.getModelId());
        snapshot.put("temperature", agent.getTemperature());
        snapshot.put("maxTokens", agent.getMaxTokens());
        snapshot.put("tokenBudget", agent.getTokenBudget());
        snapshot.put("outputSchema", agent.getOutputSchema());
        snapshot.put("teamMode", agent.getTeamMode());
        snapshot.put("teamIds", agent.getTeamIdsStr());
        snapshot.put("memoryEnabled", agent.getMemoryEnabled());
        snapshot.put("toolIds", agent.getToolIdsStr());
        snapshot.put("knowledgeIds", agent.getKnowledgeIdsStr());
        snapshot.put("mcpIds", agent.getMcpIdsStr());
        snapshot.put("workflowJson", agent.getWorkflowJson());
        snapshot.put("promptTemplateId", agent.getPromptTemplateId());
        snapshot.put("promptBindingMode", agent.getPromptBindingMode());
        snapshot.put("promptVersionId", agent.getPromptVersionId());
        snapshot.put("promptVariables", agent.getPromptVariables());
        return snapshot;
    }

    public String hashOf(Map<String, Object> snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(JsonUtils.toJsonString(snapshot).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ServiceException("配置摘要计算失败: " + e.getMessage());
        }
    }

    /** 追加一条版本快照,返回递增版本号 */
    public int createSnapshot(AgentInfo agent, String remark, String source) {
        Integer next = jdbc.queryForObject(
            "SELECT COALESCE(MAX(version_no),0)+1 FROM agent_version WHERE agent_id=?",
            Integer.class, agent.getId());
        int sequential = next == null ? 1 : next;
        // 与 agent_info.version 对齐,避免多次编辑后发布出现版本号回退
        int preferred = agent.getVersion() == null ? 1 : agent.getVersion();
        int versionNo = Math.max(sequential, preferred);
        Map<String, Object> snapshot = buildSnapshot(agent);
        jdbc.update("""
                INSERT INTO agent_version
                  (agent_id,workspace_id,version_no,snapshot_json,config_hash,remark,source,create_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, agent.getId(), agent.getWorkspaceId(), versionNo,
            JsonUtils.toJsonString(snapshot), hashOf(snapshot), remark, source, currentUserId());
        return versionNo;
    }

    public List<Map<String, Object>> listVersions(Long agentId) {
        return jdbc.queryForList("""
                SELECT id,agent_id,version_no,config_hash,remark,source,create_by,create_time
                FROM agent_version WHERE agent_id=? ORDER BY version_no DESC
                """, agentId);
    }

    /** 版本明细(含解析后的配置快照) */
    public Map<String, Object> versionDetail(Long agentId, int versionNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM agent_version WHERE agent_id=? AND version_no=?
                """, agentId, versionNo);
        if (rows.isEmpty()) {
            throw new ServiceException("版本不存在: v" + versionNo);
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        row.put("snapshot", parse(String.valueOf(row.get("snapshot_json"))));
        row.remove("snapshot_json");
        return row;
    }

    /** 逐字段比较两版配置 */
    public Map<String, Object> diff(Long agentId, int from, int to) {
        Map<String, Object> left = snapshotOf(agentId, from);
        Map<String, Object> right = snapshotOf(agentId, to);
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : left.keySet()) {
            Object before = left.get(key);
            Object after = right.get(key);
            if (!java.util.Objects.equals(str(before), str(after))) {
                changes.put(key, Map.of("from", str(before), "to", str(after)));
            }
        }
        return Map.of("from", from, "to", to, "changes", changes,
            "changedFields", changes.size());
    }

    /** 读取某版原始快照,供回滚使用 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshotOf(Long agentId, int versionNo) {
        List<String> rows = jdbc.queryForList(
            "SELECT snapshot_json FROM agent_version WHERE agent_id=? AND version_no=?",
            String.class, agentId, versionNo);
        if (rows.isEmpty()) {
            throw new ServiceException("版本不存在: v" + versionNo);
        }
        return parse(rows.getFirst());
    }

    /** 把快照值写回实体(不落库,由调用方统一 update) */
    public void applySnapshot(AgentInfo target, Map<String, Object> snapshot) {
        target.setName(text(snapshot.get("name")));
        target.setDescription(text(snapshot.get("description")));
        target.setSystemPrompt(text(snapshot.get("systemPrompt")));
        target.setWelcomeMsg(text(snapshot.get("welcomeMsg")));
        target.setSuggestedQuestions(text(snapshot.get("suggestedQuestions")));
        target.setModelId(longVal(snapshot.get("modelId")));
        Object temp = snapshot.get("temperature");
        if (temp == null) {
            target.setTemperature(null);
        } else {
            try {
                target.setTemperature(new java.math.BigDecimal(str(temp)));
            } catch (NumberFormatException ignore) {
                target.setTemperature(null);
            }
        }
        target.setMaxTokens(intVal(snapshot.get("maxTokens")));
        target.setTokenBudget(longVal(snapshot.get("tokenBudget")));
        target.setOutputSchema(text(snapshot.get("outputSchema")));
        target.setTeamMode(text(snapshot.get("teamMode")));
        target.setTeamIdsStr(text(snapshot.get("teamIds")));
        target.setMemoryEnabled(text(snapshot.get("memoryEnabled")));
        target.setToolIdsStr(text(snapshot.get("toolIds")));
        target.setKnowledgeIdsStr(text(snapshot.get("knowledgeIds")));
        target.setMcpIdsStr(text(snapshot.get("mcpIds")));
        target.setWorkflowJson(text(snapshot.get("workflowJson")));
        target.setPromptTemplateId(longVal(snapshot.get("promptTemplateId")));
        target.setPromptBindingMode(text(snapshot.get("promptBindingMode")));
        target.setPromptVersionId(longVal(snapshot.get("promptVersionId")));
        target.setPromptVariables(text(snapshot.get("promptVariables")));
    }

    private Map<String, Object> parse(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[AgentVersion] 快照解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longVal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(str(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intVal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(str(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
