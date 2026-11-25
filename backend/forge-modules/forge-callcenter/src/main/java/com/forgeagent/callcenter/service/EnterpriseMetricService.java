package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.domain.CsEnterprise;
import com.forgeagent.callcenter.domain.CsEnterpriseMetricDaily;
import com.forgeagent.callcenter.mapper.CsCallMetricDailyMapper;
import com.forgeagent.callcenter.mapper.CsEnterpriseMapper;
import com.forgeagent.callcenter.mapper.CsEnterpriseMetricDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * 企业维度统计(P1-3):把 workspace 日指标按企业归属汇总。
 *
 * <p><b>零回退</b>:{@code enterprise-metric-enabled=false}(默认)或无企业行时不做聚合,
 * 既有 workspace 口径完全不变。企业关联走 `EnterpriseQuotaService.findByWorkspace()` 同款
 * workspace_id 精确匹配;企业树(pid/idPath)的父节点汇总为后续扩展留口,当前按本企业直属 workspace 汇总。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnterpriseMetricService {

    private final CsEnterpriseMapper enterpriseMapper;
    private final CsCallMetricDailyMapper workspaceMetricMapper;
    private final CsEnterpriseMetricDailyMapper enterpriseMetricMapper;
    private final CallcenterProperties properties;

    /** 是否启用(默认关闭)。 */
    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getEnterpriseMetricEnabled());
    }

    /** 按企业+日重建指标(幂等:uk_ccemd_day)。 */
    public CsEnterpriseMetricDaily rebuild(Long enterpriseId, Date day) {
        CsEnterprise enterprise = enterpriseMapper.selectById(enterpriseId);
        if (enterprise == null || enterprise.getWorkspaceId() == null) {
            return null;
        }
        List<CsCallMetricDaily> wsRows = workspaceMetricMapper.selectList(
            new LambdaQueryWrapper<CsCallMetricDaily>()
                .eq(CsCallMetricDaily::getStatDate, day)
                .eq(CsCallMetricDaily::getWorkspaceId, enterprise.getWorkspaceId()));
        CsEnterpriseMetricDaily computed = aggregate(enterpriseId, wsRows);
        CsEnterpriseMetricDaily existing = enterpriseMetricMapper.selectOne(
            new LambdaQueryWrapper<CsEnterpriseMetricDaily>()
                .eq(CsEnterpriseMetricDaily::getEnterpriseId, enterpriseId)
                .eq(CsEnterpriseMetricDaily::getStatDate, day).last("limit 1"));
        CsEnterpriseMetricDaily row = existing == null ? new CsEnterpriseMetricDaily() : existing;
        row.setEnterpriseId(enterpriseId);
        row.setStatDate(day);
        row.setWorkspaceCount(computed.getWorkspaceCount());
        row.setCallCount(computed.getCallCount());
        row.setAnsweredCount(computed.getAnsweredCount());
        row.setAnswerRate(computed.getAnswerRate());
        row.setTransferCount(computed.getTransferCount());
        row.setAbandonCount(computed.getAbandonCount());
        row.setAbandonRate(computed.getAbandonRate());
        row.setAvgDurationSec(computed.getAvgDurationSec());
        row.setNegativeCalls(computed.getNegativeCalls());
        row.setQualityAvgScore(computed.getQualityAvgScore());
        row.setUpdateTime(new Date());
        if (existing == null) {
            row.setCreateTime(new Date());
            enterpriseMetricMapper.insert(row);
        } else {
            enterpriseMetricMapper.updateById(row);
        }
        return row;
    }

    /**
     * 纯聚合:多行 workspace 指标 → 一行企业指标(对账口径)。
     * 比率按总量重算(不取各行平均),避免加权错误;空列表返回全 0。
     */
    public static CsEnterpriseMetricDaily aggregate(Long enterpriseId, List<CsCallMetricDaily> rows) {
        CsEnterpriseMetricDaily out = new CsEnterpriseMetricDaily();
        out.setEnterpriseId(enterpriseId);
        long calls = 0, answered = 0, transfer = 0, abandon = 0, negative = 0, durationSum = 0;
        long qualitySum = 0;
        int count = 0;
        if (rows != null) {
            for (CsCallMetricDaily r : rows) {
                if (r == null) {
                    continue;
                }
                count++;
                calls += n(r.getCallCount());
                answered += n(r.getAnsweredCount());
                transfer += n(r.getTransferCount());
                abandon += n(r.getAbandonCount());
                negative += n(r.getNegativeCalls());
                durationSum += n(r.getAvgDurationSec()) * n(r.getCallCount());
                if (r.getQualityAvgScore() != null) {
                    qualitySum += r.getQualityAvgScore().longValue();
                }
            }
        }
        out.setWorkspaceCount(count);
        out.setCallCount((int) calls);
        out.setAnsweredCount((int) answered);
        out.setTransferCount((int) transfer);
        out.setAbandonCount((int) abandon);
        out.setNegativeCalls((int) negative);
        out.setAnswerRate(rate(answered, calls));
        out.setAbandonRate(rate(abandon, calls));
        out.setAvgDurationSec(calls == 0 ? 0 : (int) (durationSum / calls));
        out.setQualityAvgScore(count == 0 ? BigDecimal.ZERO
            : BigDecimal.valueOf(qualitySum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
        return out;
    }

    private static long n(Integer v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal rate(long part, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
