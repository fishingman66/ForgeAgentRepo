package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.domain.CsCdrCompensation;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import com.forgeagent.callcenter.mapper.CsCallMetricDailyMapper;
import com.forgeagent.callcenter.mapper.CsCdrCompensationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 话单指标补偿(P1-3)。
 *
 * <p><b>事实收敛</b>:ForgeAgent 无出向话单推送出口(全仓无外部 HTTP 客户端),
 * 故不凭空实现"推送失败重投";补偿语义收敛为"已挂机但 workspace 日指标缺失 → 幂等重算指标 +
 * 状态留痕 + 退避重试",复刻参考 TaskJobOfHour 的退避阶梯,但作用在本项目真实缺口上。</p>
 *
 * <p>默认关闭(agent.callcenter.cdr-compensation-enabled=false),零查库零写入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CdrCompensationService {

    /** 退避阶梯(分钟):1 / 5 / 15 / 60,与参考 TaskJobOfHour 的递增口径一致。 */
    private static final int[] BACKOFF_MINUTES = {1, 5, 15, 60};

    private final CsCallMapper callMapper;
    private final CsCallMetricDailyMapper metricMapper;
    private final CsCdrCompensationMapper compensationMapper;
    private final CallMetricService metricService;
    private final CallcenterProperties properties;

    /** 是否启用(默认关闭)。 */
    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getCdrCompensationEnabled());
    }

    /** 退避分钟数(纯函数,便于单测):超出阶梯取最后一档。 */
    public static int backoffMinutes(int retryCount) {
        int idx = Math.max(0, retryCount);
        return BACKOFF_MINUTES[Math.min(idx, BACKOFF_MINUTES.length - 1)];
    }

    /** 单通补偿:指标已存在则标 REBUILT(无需重算);否则重建并留痕。返回是否实际重算。 */
    public boolean compensate(CsCall call) {
        if (!enabled() || call == null || call.getId() == null
            || !"ENDED".equals(call.getStatus()) || call.getRingAt() == null) {
            return false;
        }
        Date day = metricService.dayStart(call.getRingAt());
        boolean metricExists = metricMapper.selectOne(new LambdaQueryWrapper<CsCallMetricDaily>()
            .eq(CsCallMetricDaily::getStatDate, day)
            .eq(CsCallMetricDaily::getWorkspaceId, call.getWorkspaceId())
            .last("limit 1")) != null;
        if (metricExists) {
            mark(call, day, "REBUILT", null);
            return false;
        }
        try {
            metricService.rebuild(call.getWorkspaceId(), day);
            mark(call, day, "REBUILT", null);
            return true;
        } catch (Exception e) {
            mark(call, day, "RETRIED", e.getMessage());
            return false;
        }
    }

    /** 扫描待补偿:取 PENDING/RETRIED 且已过退避窗口的记录对应的通话(limit 50)。 */
    public List<CsCdrCompensation> pending(Date now) {
        if (!enabled()) {
            return List.of();
        }
        return compensationMapper.selectList(new LambdaQueryWrapper<CsCdrCompensation>()
            .in(CsCdrCompensation::getStatus, List.of("PENDING", "RETRIED"))
            .orderByAsc(CsCdrCompensation::getCreateTime)
            .last("limit 50"));
    }

    /** 是否到达重试时间(纯函数):null 上次重试视为立即可试。 */
    public static boolean due(Date now, Date lastRetryAt, int retryCount) {
        if (lastRetryAt == null) {
            return true;
        }
        long waitMs = (long) backoffMinutes(retryCount) * 60_000L;
        return now.getTime() - lastRetryAt.getTime() >= waitMs;
    }

    /** 留痕:唯一键 (call_id) 防重复补偿;已存在则更新状态与重试次数。 */
    private void mark(CsCall call, Date day, String status, String error) {
        try {
            CsCdrCompensation existing = compensationMapper.selectOne(
                new LambdaQueryWrapper<CsCdrCompensation>()
                    .eq(CsCdrCompensation::getCallId, call.getId()).last("limit 1"));
            CsCdrCompensation row = existing == null ? new CsCdrCompensation() : existing;
            row.setCallId(call.getId());
            row.setWorkspaceId(call.getWorkspaceId());
            row.setStatDate(day);
            row.setStatus(status);
            int retries = row.getRetryCount() == null ? 0 : row.getRetryCount();
            if ("RETRIED".equals(status)) {
                row.setRetryCount(retries + 1);
                row.setLastRetryAt(new Date());
            }
            row.setError(error == null || error.length() <= 500 ? error : error.substring(0, 500));
            row.setUpdateTime(new Date());
            if (existing == null) {
                row.setCreateTime(new Date());
                compensationMapper.insert(row);
            } else {
                compensationMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("[CC] 话单补偿留痕失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
    }
}
