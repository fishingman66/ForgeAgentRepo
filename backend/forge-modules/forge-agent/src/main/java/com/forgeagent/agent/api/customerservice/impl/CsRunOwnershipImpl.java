package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsRunOwnership;
import com.forgeagent.agent.service.run.RunTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 运行归属校验(C4):控制台按登录用户,客服按访客 + 渠道。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsRunOwnershipImpl implements CsRunOwnership {

    private final RunTraceService runTrace;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean isOwnedByUser(String runId, Long userId) {
        if (runId == null || userId == null) {
            return false;
        }
        Long owner = runTrace.ownerOf(runId);
        return owner != null && owner.equals(userId);
    }

    @Override
    public boolean isOwnedByVisitor(String runId, Long visitorId, String channelCode) {
        if (runId == null || visitorId == null || channelCode == null) {
            return false;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT visitor_id, channel_code FROM agent_run WHERE run_id = ? LIMIT 1", runId);
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Object> row = rows.get(0);
            return visitorId.equals(asLong(row.get("visitor_id")))
                && channelCode.equals(String.valueOf(row.get("channel_code")));
        } catch (Exception e) {
            log.warn("[CS] 访客归属校验失败 runId={}: {}", runId, e.getMessage());
            return false;
        }
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
