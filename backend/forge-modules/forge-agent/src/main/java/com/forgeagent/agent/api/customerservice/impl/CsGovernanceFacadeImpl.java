package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsGovernanceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 客服治理契约实现(C4):挂靠 agent_info 的发布状态与版本号,不另建治理表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsGovernanceFacadeImpl implements CsGovernanceFacade {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean canPublish(Long agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            var rows = jdbcTemplate.queryForList(
                "SELECT status, model_id, system_prompt FROM agent_info WHERE id = ? LIMIT 1", agentId);
            if (rows.isEmpty()) {
                return false;
            }
            var row = rows.get(0);
            Object status = row.get("status");
            Object modelId = row.get("model_id");
            Object prompt = row.get("system_prompt");
            // 0草稿/1发布/2停用;停用态禁止应用模板
            return !"2".equals(String.valueOf(status)) && modelId != null
                && prompt != null && !String.valueOf(prompt).isBlank();
        } catch (Exception e) {
            log.warn("[CS] 发布门禁检查失败 agentId={}: {}", agentId, e.getMessage());
            return false;
        }
    }

    @Override
    public Long currentPublishedVersion(Long agentId) {
        if (agentId == null) {
            return null;
        }
        try {
            Long version = jdbcTemplate.queryForObject(
                "SELECT published_version FROM agent_info WHERE id = ? LIMIT 1", Long.class, agentId);
            return version;
        } catch (Exception e) {
            return null;
        }
    }
}
