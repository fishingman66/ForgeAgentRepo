package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.mapper.CsKgExpandMetricMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 图谱检索扩展效果指标(P3 / M4 后续):按空间日聚合检索与零命中,支撑「对比开关」的评估。
 *
 * <p>三个读数:总检索、带扩展词的检索、两者各自的零命中次数。
 * 扩展的价值假设是「扩展后的检索零命中率低于未扩展」;若扩展侧零命中率不降反升,
 * 说明领域实体登记偏了或扩展词上限不合理,先去维护页清理知识再调参。</p>
 *
 * <p>埋点失败只告警:指标是旁路,不能让一次 upsert 拖垮回答。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsKgExpandMetricService {

    private final CsKgExpandMetricMapper metricMapper;

    /** 每次知识检索后埋点一次 */
    public void record(Long workspaceId, boolean expanded, boolean zeroHit) {
        if (workspaceId == null || workspaceId < 0) {
            return;
        }
        try {
            metricMapper.upsert(workspaceId, LocalDate.now(),
                expanded ? 1 : 0, zeroHit ? 1 : 0, expanded && zeroHit ? 1 : 0);
        } catch (Exception e) {
            log.warn("[CS-KgMetric] 指标写入失败(不影响检索): {}", e.getMessage());
        }
    }

    /** 最近 N 天(1~90)的日聚合行,零命中率由前端按行计算并对比 */
    public List<Map<String, Object>> list(Long workspaceId, Integer days) {
        if (workspaceId == null || workspaceId < 0) {
            return List.of();
        }
        int window = days == null ? 7 : Math.max(1, Math.min(90, days));
        return metricMapper.listRecent(workspaceId, window);
    }
}
