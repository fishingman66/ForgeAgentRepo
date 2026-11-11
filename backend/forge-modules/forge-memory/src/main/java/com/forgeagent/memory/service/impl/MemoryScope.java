package com.forgeagent.memory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.memory.domain.AgentMemory;

/**
 * 长期记忆的「写入范围」判定(P3 / M1.5 缺陷修复 D1)。
 *
 * <p>冲突替代 UPDATE 与事实键查询必须使用完全相同的范围口径:userId 非空只命中该用户的行,
 * 为空只命中平台级(userId IS NULL)行。早期两处口径不一致(键池只按 agentId、UPDATE 不带 userId),
 * 会把其他用户的同键记忆改写掉,并让新用户的事实走「替代」而不是「新增」。</p>
 *
 * @author ForgeAgent
 */
final class MemoryScope {

    private MemoryScope() {
    }

    /** 事实键候选池:与 {@link #conflictUpdate} 同范围,按 id 倒序避免漏掉新键 */
    static LambdaQueryWrapper<AgentMemory> factKeyQuery(Long agentId, Long userId) {
        return new LambdaQueryWrapper<AgentMemory>()
            .select(AgentMemory::getFactKey)
            .eq(AgentMemory::getAgentId, agentId)
            .eq(userId != null, AgentMemory::getUserId, userId)
            .isNull(userId == null, AgentMemory::getUserId)
            .isNotNull(AgentMemory::getFactKey)
            .orderByDesc(AgentMemory::getId)
            .last("limit 500");
    }

    /** 冲突替代作用域:只覆盖同一 agent + 同一用户(或平台级)的同键记忆 */
    static LambdaUpdateWrapper<AgentMemory> conflictUpdate(Long agentId, String factKey, Long userId) {
        return new LambdaUpdateWrapper<AgentMemory>()
            .eq(AgentMemory::getAgentId, agentId)
            .eq(AgentMemory::getFactKey, factKey)
            .eq(userId != null, AgentMemory::getUserId, userId)
            .isNull(userId == null, AgentMemory::getUserId);
    }

    /**
     * 读范围:本人记忆 + 平台级(userId 为空)记忆。
     * 注入、语义召回、补偿扫描三处必须一致,否则平台级记忆会漏召回。
     */
    static void applyReadScope(LambdaQueryWrapper<AgentMemory> wrapper, Long userId) {
        if (userId != null) {
            wrapper.and(w -> w.eq(AgentMemory::getUserId, userId).or().isNull(AgentMemory::getUserId));
        } else {
            wrapper.isNull(AgentMemory::getUserId);
        }
    }
}
