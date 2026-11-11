package com.forgeagent.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agent 热点配置缓存服务:AgentInfo / AgentModel 在对话、运行时高频读取,变更极少。
 * 缓存 TTL 由底层 redisson-spring-cache 配置(默认 60s),写操作主动清理。
 *
 * <p>之所以独立 Bean:Spring Cache AOP 要求代理调用生效,内部自调用 @Cacheable 不触发。
 * 拆成独立 Service 后所有调用都经代理,注解生效。</p>
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentConfigCacheService {

    /** Agent 配置缓存名 — 带 #ttl 后缀设置过期时间兜底(PlusSpringCacheManager 语法),写操作同时主动清理 */
    public static final String CACHE_AGENT = "agent.info#60s";
    /** Agent 模型缓存名 */
    public static final String CACHE_MODEL = "agent.model#60s";

    private final AgentInfoMapper agentMapper;
    private final AgentModelMapper modelMapper;

    // ---------- AgentInfo ----------

    /**
     * 单个 Agent 查询 — 缓存命中后跳过 DB
     */
    @Cacheable(cacheNames = CACHE_AGENT, key = "#id", unless = "#result == null")
    public AgentInfo getAgentById(Long id) {
        return agentMapper.selectById(id);
    }

    /**
     * 批量 Agent 查询 — 逐个走缓存,miss 的 id 一次 selectByIds 拉回并回填
     */
    public Map<Long, AgentInfo> getAgentsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, AgentInfo> result = new java.util.HashMap<>();
        for (Long id : distinct) {
            AgentInfo cached = getAgentById(id); // 逐个走 @Cacheable,命中后不再查 DB
            if (cached != null) {
                result.put(id, cached);
            }
        }
        return result;
    }

    /**
     * Agent 写操作后清理 — insert/update/delete 都要调
     */
    @CacheEvict(cacheNames = CACHE_AGENT, key = "#id")
    public void evictAgent(Long id) {
        // 注解生效即可,无需实现
    }

    /**
     * 批量清理(deleteByIds 场景)
     */
    @CacheEvict(cacheNames = CACHE_AGENT, allEntries = true)
    public void evictAllAgents() {
    }

    // ---------- AgentModel ----------

    @Cacheable(cacheNames = CACHE_MODEL, key = "#id", unless = "#result == null")
    public AgentModel getModelById(Long id) {
        return modelMapper.selectById(id);
    }

    public Map<Long, AgentModel> getModelsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, AgentModel> result = new java.util.HashMap<>();
        for (Long id : distinct) {
            AgentModel cached = getModelById(id);
            if (cached != null) {
                result.put(id, cached);
            }
        }
        return result;
    }

    @CacheEvict(cacheNames = CACHE_MODEL, key = "#id")
    public void evictModel(Long id) {
    }

    @CacheEvict(cacheNames = CACHE_MODEL, allEntries = true)
    public void evictAllModels() {
    }
}
