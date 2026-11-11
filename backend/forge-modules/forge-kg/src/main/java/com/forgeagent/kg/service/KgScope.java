package com.forgeagent.kg.service;

import com.forgeagent.common.core.utils.StringUtils;

import java.util.Locale;

/**
 * 图谱范围(forge-kg):一段图归谁所有,由「范围类型 + 范围ID + 用户ID」三元组决定。
 *
 * <p>之所以不直接叫 {@code agentId},是因为图谱不只服务模型记忆:
 * 智能客服的领域知识(产品、故障、话术、意图)按工作空间或渠道组织,
 * 用通用 scope 才能让同一套存储/召回被多个域复用。</p>
 *
 * <p>{@code userId} 为空 = 该范围内所有用户共享;落库统一映射为 0
 * (不用数据库 NULL —— MySQL 唯一索引对 NULL 不去重,会让共享节点重复插入)。</p>
 *
 * @param scopeType 范围类型,如 agent / workspace / channel;小写
 * @param scopeId   范围ID,如 agentId / workspaceId
 * @param userId    用户ID,可为空(共享级)
 * @author ForgeAgent
 */
public record KgScope(String scopeType, Long scopeId, Long userId) {

    /** 长期记忆使用的范围类型 */
    public static final String AGENT = "agent";

    /** 智能客服领域图谱建议使用的范围类型 */
    public static final String WORKSPACE = "workspace";

    public KgScope {
        scopeType = StringUtils.isBlank(scopeType)
            ? AGENT : scopeType.trim().toLowerCase(Locale.ROOT);
    }

    /** 长期记忆:按 Agent + 用户隔离,与 agent_memory 的口径一致 */
    public static KgScope agent(Long agentId, Long userId) {
        return new KgScope(AGENT, agentId, userId);
    }

    /** 客服领域:按工作空间组织,userId 可空表示空间共享 */
    public static KgScope workspace(Long workspaceId, Long userId) {
        return new KgScope(WORKSPACE, workspaceId, userId);
    }

    /** 落库用的用户标识:共享级统一为 0 */
    public long userIdValue() {
        return userId == null ? 0L : userId;
    }

    /** 范围是否有效:缺 scopeId 的写入必须拒绝,否则会污染所有范围 */
    public boolean valid() {
        return StringUtils.isNotBlank(scopeType) && scopeId != null;
    }
}
