package com.forgeagent.memory.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 记忆治理 SQL(P3 / M4:L4 消息归档 + L5 TTL/配额)。
 *
 * <p>操作 agent_message / agent_session / agent_info 三张他域表,刻意用纯 SQL
 * 而不引实体:forge-memory 不能依赖 forge-agent(方向是 agent → memory),
 * 归档与淘汰是表到表转移,没有实体也说得清楚。UPDATE/DELETE 一律带范围条件。</p>
 *
 * @author ForgeAgent
 */
public interface MemoryGovernanceMapper {

    /** 消息归档一批:未置顶且最后消息时间超期的会话,整会话转移;返回本批条数 */
    @Insert("INSERT INTO agent_message_archive "
        + "(id, session_id, role, content, ext_json, feedback, feedback_comment, feedback_tags, "
        + "feedback_time, citations, create_dept, create_by, create_time, update_by, update_time, "
        + "attachments, client_msg_id, sender_type, ticket_id, owner_user_id, archive_reason, archive_time) "
        + "SELECT m.id, m.session_id, m.role, m.content, m.ext_json, m.feedback, m.feedback_comment, "
        + "m.feedback_tags, m.feedback_time, m.citations, m.create_dept, m.create_by, m.create_time, "
        + "m.update_by, m.update_time, m.attachments, m.client_msg_id, m.sender_type, m.ticket_id, "
        + "s.user_id, 'RETENTION', #{stamp} "
        + "FROM agent_message m JOIN agent_session s ON m.session_id = s.id "
        + "WHERE (s.pinned IS NULL OR s.pinned = '0') "
        + "AND s.last_msg_time < DATE_SUB(NOW(), INTERVAL #{retentionDays} DAY) "
        + "LIMIT #{batch}")
    int archiveMessages(@Param("retentionDays") int retentionDays,
                        @Param("batch") int batch,
                        @Param("stamp") Object stamp);

    /** 删除已入档(本批)的主表消息:按归档表 id 对账,不重不漏 */
    @Delete("DELETE m FROM agent_message m "
        + "JOIN agent_message_archive a ON a.id = m.id "
        + "WHERE a.archive_reason = 'RETENTION' AND a.archive_time = #{stamp}")
    int deleteArchivedMessages(@Param("stamp") Object stamp);

    /** TTL 归档一批:显式 expire_at 已过期,或 ttl-days>0 时的隐式过期(创建时间超期);id 保留进归档表对账 */
    @Insert("INSERT INTO agent_memory_archive "
        + "(id, agent_id, workspace_id, user_id, content, fact_key, importance, hit_count, "
        + "source_session_id, archive_reason, archive_time, create_time) "
        + "SELECT id, agent_id, workspace_id, user_id, content, fact_key, importance, "
        + "IFNULL(hit_count, 0), source_session_id, 'TTL', #{stamp}, create_time "
        + "FROM agent_memory "
        + "WHERE (expire_at IS NOT NULL AND expire_at < NOW()) "
        + "OR (expire_at IS NULL AND #{ttlDays} > 0 "
        + "AND create_time < DATE_SUB(NOW(), INTERVAL #{ttlDays} DAY)) "
        + "LIMIT #{batch}")
    int archiveExpiredMemories(@Param("ttlDays") int ttlDays,
                               @Param("batch") int batch,
                               @Param("stamp") Object stamp);

    @Delete("DELETE m FROM agent_memory m "
        + "JOIN agent_memory_archive a ON a.id = m.id "
        + "WHERE a.archive_reason = 'TTL' AND a.archive_time = #{stamp}")
    int deleteExpiredMemories(@Param("stamp") Object stamp);

    /**
     * 超配额组盘点:只看个人记忆(平台级不占个人配额),
     * effective = COALESCE(NULLIF(agent_info.memory_quota, 0), 默认配额),0 表示不限。
     */
    @Select("SELECT m.agent_id AS agentId, m.user_id AS userId, COUNT(*) AS cnt, "
        + "COALESCE(NULLIF(a.memory_quota, 0), #{defaultQuota}) AS quota "
        + "FROM agent_memory m LEFT JOIN agent_info a ON a.id = m.agent_id "
        + "WHERE m.user_id IS NOT NULL "
        + "GROUP BY m.agent_id, m.user_id, a.memory_quota")
    List<Map<String, Object>> countOverQuotaGroups(@Param("defaultQuota") int defaultQuota);

    /** 单条记忆归档(配额淘汰);embedding 向量不归档,重新激活时由补偿扫描重算 */
    @Insert("INSERT INTO agent_memory_archive "
        + "(id, agent_id, workspace_id, user_id, content, fact_key, importance, hit_count, "
        + "source_session_id, archive_reason, archive_time, create_time) "
        + "VALUES (#{m.id}, #{m.agentId}, #{m.workspaceId}, #{m.userId}, #{m.content}, #{m.factKey}, "
        + "#{m.importance}, IFNULL(#{m.hitCount}, 0), #{m.sourceSessionId}, #{reason}, NOW(), #{m.createTime})")
    int archiveMemory(@Param("m") com.forgeagent.memory.domain.AgentMemory memory,
                      @Param("reason") String reason);

    /** 遗忘:按归属用户物理删除记忆归档行 */
    @Delete("DELETE FROM agent_memory_archive WHERE user_id = #{userId}")
    int deleteMemoryArchiveByUser(@Param("userId") Long userId);

    /** 遗忘:按归属用户物理删除消息归档行(归档时冗余的 owner_user_id) */
    @Delete("DELETE FROM agent_message_archive WHERE owner_user_id = #{userId}")
    int deleteMessageArchiveByUser(@Param("userId") Long userId);

    /** 遗忘:该用户消息归档行数(返回删除量前先数一次) */
    @Select("SELECT COUNT(*) FROM agent_message_archive WHERE owner_user_id = #{userId}")
    long countMessageArchiveByUser(@Param("userId") Long userId);
}
