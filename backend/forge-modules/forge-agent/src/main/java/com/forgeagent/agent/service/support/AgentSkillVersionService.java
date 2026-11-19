package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 技能内容版本服务(08方案 / P1-B)。
 *
 * <p>技能是跨 Agent 共享资源,回滚不能改写 {@code agent_skill} 本体——那会污染装了同一技能的
 * 其他 Agent。因此内容变更一律归档到 {@code agent_skill_version},由
 * {@code agent_skill_install.pinned_version} 钉住各 Agent 实际使用的版本:
 * 发布时把 pin 固定为当前版本,回滚时恢复快照里的 pin。冻结语义等价,且互不影响。</p>
 *
 * <p>{@link #contentHash(AgentSkill)} 是快照 {@code skillRefs[i].contentHash} 的唯一来源,
 * 算法必须稳定(定序拼接 + 定长分隔符),否则同一内容会算出两个值让 config_hash 抖动。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
public class AgentSkillVersionService {

    /** 字段分隔符:用不可见控制符,避免内容里的普通字符造成拼接歧义 */
    private static final char SEP = '\u0001';

    private final JdbcTemplate jdbc;

    public AgentSkillVersionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 技能内容摘要:名称/摘要/手册/三类引用/提示词片段,按固定顺序拼接后 SHA-256。
     * 不含 version 与 status —— 版本号本身就是内容的产物,停用属于运行时开关。
     */
    public String contentHash(AgentSkill skill) {
        if (skill == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(1024);
        append(sb, skill.getName());
        append(sb, skill.getDescription());
        append(sb, skill.getInstructions());
        append(sb, skill.getToolIds());
        append(sb, skill.getMcpIds());
        append(sb, skill.getKnowledgeIds());
        append(sb, skill.getPromptFragment());
        return sha256(sb.toString());
    }

    /**
     * 归档当前内容为一条版本记录(幂等)。同一 (skill_id, version_no) 重复归档会覆盖,
     * 用于补齐存量数据缺失的 content_hash。
     */
    public void archive(AgentSkill skill) {
        if (skill == null || skill.getId() == null) {
            return;
        }
        int versionNo = skill.getVersion() == null ? 1 : skill.getVersion();
        jdbc.update("""
                INSERT INTO agent_skill_version
                  (skill_id,version_no,name,description,instructions,tool_ids,mcp_ids,
                   knowledge_ids,prompt_fragment,content_hash,create_by,create_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW())
                ON DUPLICATE KEY UPDATE
                  name=VALUES(name), description=VALUES(description), instructions=VALUES(instructions),
                  tool_ids=VALUES(tool_ids), mcp_ids=VALUES(mcp_ids),
                  knowledge_ids=VALUES(knowledge_ids), prompt_fragment=VALUES(prompt_fragment),
                  content_hash=VALUES(content_hash)
                """, skill.getId(), versionNo, skill.getName(), nvl(skill.getDescription()),
            nvl(skill.getInstructions()), nvl(skill.getToolIds()), nvl(skill.getMcpIds()),
            nvl(skill.getKnowledgeIds()), nvl(skill.getPromptFragment()),
            contentHash(skill), currentUserId());
    }

    /**
     * 批量确保版本行存在(发布前调用),避免快照里出现 contentHash=null
     */
    public void archiveAll(List<AgentSkill> skills) {
        for (AgentSkill skill : skills) {
            try {
                archive(skill);
            } catch (Exception e) {
                log.warn("[SkillVersion] 技能 {} 归档失败: {}", skill.getId(), e.getMessage());
            }
        }
    }

    /**
     * 按 ID 批量归档(发布快照前调用)。这里自带实体查询,避免调用方为了拿
     * instructions 大字段再走一次 ORM。
     */
    public void archiveBySkillIds(java.util.Collection<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(skillIds.size(), "?"));
        List<AgentSkill> skills = jdbc.query(
            "SELECT id,name,description,instructions,tool_ids,mcp_ids,knowledge_ids,"
                + "prompt_fragment,COALESCE(version,1) AS version FROM agent_skill WHERE id IN (" + placeholders + ")",
            (rs, rowNum) -> {
                AgentSkill skill = new AgentSkill();
                skill.setId(rs.getLong("id"));
                skill.setName(rs.getString("name"));
                skill.setDescription(rs.getString("description"));
                skill.setInstructions(rs.getString("instructions"));
                skill.setToolIds(rs.getString("tool_ids"));
                skill.setMcpIds(rs.getString("mcp_ids"));
                skill.setKnowledgeIds(rs.getString("knowledge_ids"));
                skill.setPromptFragment(rs.getString("prompt_fragment"));
                skill.setVersion(rs.getInt("version"));
                return skill;
            }, skillIds.toArray());
        archiveAll(skills);
    }

    /** 读取某版本内容行,缺失返回空 Map */
    public Map<String, Object> versionOf(Long skillId, Integer versionNo) {
        if (skillId == null || versionNo == null) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT skill_id,version_no,name,description,instructions,tool_ids,mcp_ids,
                       knowledge_ids,prompt_fragment,content_hash
                FROM agent_skill_version WHERE skill_id=? AND version_no=?
                """, skillId, versionNo);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    /** 某版本的 contentHash,缺失返回 null */
    public String contentHashOf(Long skillId, Integer versionNo) {
        Object hash = versionOf(skillId, versionNo).get("content_hash");
        return hash == null ? null : String.valueOf(hash);
    }

    /**
     * 按 pin 覆盖技能内容(只构造视图,不改库)。
     *
     * <p>pin 为空或等于当前版本时原样返回;版本行缺失时降级为当前内容并告警——
     * 宁可给最新内容,也不要让装配链路因历史数据不全直接失败。</p>
     */
    public AgentSkill applyPinned(AgentSkill skill, Integer pinnedVersion) {
        if (skill == null || pinnedVersion == null) {
            return skill;
        }
        int current = skill.getVersion() == null ? 1 : skill.getVersion();
        if (pinnedVersion == current) {
            return skill;
        }
        Map<String, Object> row = versionOf(skill.getId(), pinnedVersion);
        if (row.isEmpty()) {
            log.warn("[SkillVersion] 技能 {} 的 v{} 内容缺失,降级使用当前 v{}",
                skill.getId(), pinnedVersion, current);
            return skill;
        }
        AgentSkill view = new AgentSkill();
        view.setId(skill.getId());
        view.setVersion(pinnedVersion);
        view.setStatus(skill.getStatus());
        view.setWorkspaceId(skill.getWorkspaceId());
        view.setName(text(row.get("name"), skill.getName()));
        view.setDescription(text(row.get("description"), skill.getDescription()));
        view.setInstructions(text(row.get("instructions"), skill.getInstructions()));
        view.setToolIds(text(row.get("tool_ids"), skill.getToolIds()));
        view.setMcpIds(text(row.get("mcp_ids"), skill.getMcpIds()));
        view.setKnowledgeIds(text(row.get("knowledge_ids"), skill.getKnowledgeIds()));
        view.setPromptFragment(text(row.get("prompt_fragment"), skill.getPromptFragment()));
        return view;
    }

    private static void append(StringBuilder sb, String value) {
        sb.append(value == null ? "" : value).append(SEP);
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("技能内容摘要计算失败: " + e.getMessage(), e);
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
