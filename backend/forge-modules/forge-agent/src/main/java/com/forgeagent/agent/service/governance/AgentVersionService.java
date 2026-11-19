package com.forgeagent.agent.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agent.service.support.AgentSkillVersionService;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
    private final AgentSkillVersionService skillVersionService;

    public AgentVersionService(JdbcTemplate jdbc, AgentSkillVersionService skillVersionService) {
        this.jdbc = jdbc;
        this.skillVersionService = skillVersionService;
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
        // P1-B:技能绑定纳入快照(技能存在独立表,不在 agent_info 字段里)
        snapshot.put("skillRefs", skillRefsOf(agent.getId()));
        return snapshot;
    }

    /**
     * 技能引用列表,按 skill_id 排序保证 hash 稳定。
     *
     * <p>version 取「被钉住的版本」,没钉则取技能当前版本;contentHash 取自
     * {@code agent_skill_version},让「只改技能手册」也能改变 config_hash。</p>
     */
    public List<Map<String, Object>> skillRefsOf(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT i.skill_id                       AS id,
                       COALESCE(i.pinned_version, s.version, 1) AS version,
                       s.name                           AS name,
                       v.content_hash                   AS contentHash
                FROM agent_skill_install i
                JOIN agent_skill s ON s.id = i.skill_id AND s.del_flag = 0
                LEFT JOIN agent_skill_version v
                       ON v.skill_id = i.skill_id
                      AND v.version_no = COALESCE(i.pinned_version, s.version, 1)
                WHERE i.agent_id = ? AND i.del_flag = 0
                ORDER BY i.skill_id
                """, agentId);
    }

    /**
     * 把该 Agent 的安装关系钉到技能当前版本。
     * 发布语义 = 冻结此刻,所以既有的旧 pin 会被覆盖。
     */
    public void pinCurrentVersions(Long agentId) {
        if (agentId == null) {
            return;
        }
        int affected = jdbc.update("""
                UPDATE agent_skill_install i
                JOIN agent_skill s ON s.id = i.skill_id
                SET i.pinned_version = COALESCE(s.version, 1)
                WHERE i.agent_id = ? AND i.del_flag = 0
                """, agentId);
        if (affected > 0) {
            log.debug("[AgentVersion] 应用 {} 的技能绑定已钉到当前版本, {} 条", agentId, affected);
        }
    }

    /**
     * 按快照恢复技能安装关系(回滚用)。
     *
     * <p>快照里的 skillRefs 是目标集合的完整定义,因此做覆盖式同步:多余的物理删除、
     * 缺失的补插、交集的 pin 改回快照值。老快照没有 skillRefs 时**跳过而不是清空**,
     * 否则会把现有安装误删。</p>
     */
    public void restoreSkillBinding(Long agentId, Map<String, Object> snapshot) {
        if (agentId == null || snapshot == null) {
            return;
        }
        Object raw = snapshot.get("skillRefs");
        if (!(raw instanceof List<?> refs)) {
            log.warn("[AgentVersion] 快照缺少 skillRefs(引入 P1-B 之前的版本),回滚跳过技能绑定恢复: agentId={}", agentId);
            return;
        }
        Map<Long, Integer> target = new LinkedHashMap<>();
        for (Object item : refs) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Long id = longVal(row.get("id") != null ? row.get("id") : row.get("skillId"));
            if (id != null) {
                target.put(id, intVal(row.get("version")));
            }
        }

        List<Map<String, Object>> current = jdbc.queryForList(
            "SELECT id,skill_id FROM agent_skill_install WHERE agent_id=? AND del_flag=0", agentId);
        Map<Long, Long> currentRows = new LinkedHashMap<>();
        for (Map<String, Object> row : current) {
            currentRows.put(longVal(row.get("skill_id")), longVal(row.get("id")));
        }

        // 1. 快照里没有的绑定:物理删除(逻辑删除会占住 uk_skill_agent)
        for (Map.Entry<Long, Long> entry : currentRows.entrySet()) {
            if (!target.containsKey(entry.getKey())) {
                jdbc.update("DELETE FROM agent_skill_install WHERE id=?", entry.getValue());
            }
        }
        // 2. 快照里的绑定:存在的改 pin,缺失的补插(workspace_id 由技能行带出)
        for (Map.Entry<Long, Integer> entry : target.entrySet()) {
            Long skillId = entry.getKey();
            Integer version = entry.getValue();
            if (currentRows.containsKey(skillId)) {
                jdbc.update("UPDATE agent_skill_install SET pinned_version=? WHERE agent_id=? AND skill_id=?",
                    version, agentId, skillId);
            } else {
                jdbc.update("""
                        INSERT INTO agent_skill_install (skill_id,agent_id,workspace_id,pinned_version,create_time)
                        SELECT s.id, ?, s.workspace_id, ?, NOW() FROM agent_skill s
                        WHERE s.id=? AND s.del_flag=0
                        """, agentId, version, skillId);
            }
        }
        log.info("[AgentVersion] 应用 {} 技能绑定已按快照恢复: 目标 {} 条", agentId, target.size());
    }

    public String hashOf(Map<String, Object> snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(toJson(snapshot).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ServiceException("配置摘要计算失败: " + e.getMessage());
        }
    }

    /**
     * 不走 JsonUtils:它静态初始化要 SpringUtil 拿 BeanFactory,纯单测环境会
     * ExceptionInInitializerError(同 ReleaseGateService 的取舍)。快照里只有
     * 字符串/数字/集合,两个 ObjectMapper 的输出一致,hash 口径不受影响。
     */
    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException("配置序列化失败: " + e.getMessage());
        }
    }

    /** 追加一条版本快照(发布语义:顺带把技能绑定钉到当前版本) */
    public int createSnapshot(AgentInfo agent, String remark, String source) {
        return createSnapshot(agent, remark, source, true);
    }

    /**
     * 追加一条版本快照。
     *
     * @param pinSkills true=发布,把安装关系钉到技能当前版本;
     *                  false=回滚,保留 {@link #restoreSkillBinding} 刚恢复的旧 pin,
     *                  否则会把刚回滚好的版本又钉回最新,等于回滚失效
     */
    public int createSnapshot(AgentInfo agent, String remark, String source, boolean pinSkills) {
        Integer next = jdbc.queryForObject(
            "SELECT COALESCE(MAX(version_no),0)+1 FROM agent_version WHERE agent_id=?",
            Integer.class, agent.getId());
        int sequential = next == null ? 1 : next;
        // 与 agent_info.version 对齐,避免多次编辑后发布出现版本号回退
        int preferred = agent.getVersion() == null ? 1 : agent.getVersion();
        int versionNo = Math.max(sequential, preferred);
        // P1-B:先归档内容再钉版本,保证快照里的 contentHash 非空且与 pin 一致
        List<Long> skillIds = jdbc.queryForList(
            "SELECT skill_id FROM agent_skill_install WHERE agent_id=? AND del_flag=0",
            Long.class, agent.getId());
        skillVersionService.archiveBySkillIds(skillIds);
        if (pinSkills) {
            pinCurrentVersions(agent.getId());
        }
        Map<String, Object> snapshot = buildSnapshot(agent);
        jdbc.update("""
                INSERT INTO agent_version
                  (agent_id,workspace_id,version_no,snapshot_json,config_hash,remark,source,create_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, agent.getId(), agent.getWorkspaceId(), versionNo,
            toJson(snapshot), hashOf(snapshot), remark, source, currentUserId());
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
            if (!java.util.Objects.equals(stringify(key, before), stringify(key, after))) {
                changes.put(key, Map.of("from", stringify(key, before), "to", stringify(key, after)));
            }
        }
        // 只存在于右侧的字段(老快照升级后新增的 key)也要报出来
        for (String key : right.keySet()) {
            if (!left.containsKey(key)) {
                changes.put(key, Map.of("from", "", "to", stringify(key, right.get(key))));
            }
        }
        return Map.of("from", from, "to", to, "changes", changes,
            "changedFields", changes.size());
    }

    /**
     * diff 输出的人类可读化:skillRefs 是结构化列表,直接 toString 会变成一长串 JSON
     */
    private static String stringify(String key, Object value) {
        if (value == null) {
            return "";
        }
        if ("skillRefs".equals(key) && value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> row) {
                    Object name = row.get("name") != null ? row.get("name") : row.get("id");
                    Object version = row.get("version");
                    parts.add(String.valueOf(name) + " v" + (version == null ? "?" : version));
                }
            }
            return String.join("、", parts);
        }
        return String.valueOf(value);
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
