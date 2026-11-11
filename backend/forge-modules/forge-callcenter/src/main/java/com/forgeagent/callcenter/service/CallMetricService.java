package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.mapper.CsCallMetricDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 通话日指标(V2):挂机触发按 workspace + 自然日全量重算,幂等可对账。
 * 指标口径:接通率、平均时长、机器人自助解决率(无工单占比)、录音覆盖率。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallMetricService {

    private final com.forgeagent.callcenter.mapper.CsCallMapper callMapper;
    private final CsCallMetricDailyMapper metricMapper;

    public CsCallMetricDaily rebuild(Long workspaceId, Date day) {
        Date from = dayStart(day);
        Date to = new Date(from.getTime() + 86_400_000L);
        List<com.forgeagent.callcenter.domain.CsCall> calls = callMapper.selectList(
            new LambdaQueryWrapper<com.forgeagent.callcenter.domain.CsCall>()
                .eq(com.forgeagent.callcenter.domain.CsCall::getWorkspaceId, workspaceId)
                .ge(com.forgeagent.callcenter.domain.CsCall::getRingAt, from)
                .lt(com.forgeagent.callcenter.domain.CsCall::getRingAt, to));
        CsCallMetricDaily computed = compute(calls);
        Date now = new Date();
        CsCallMetricDaily existing = metricMapper.selectOne(
            new LambdaQueryWrapper<CsCallMetricDaily>()
                .eq(CsCallMetricDaily::getStatDate, from)
                .eq(CsCallMetricDaily::getWorkspaceId, workspaceId).last("limit 1"));
        CsCallMetricDaily row = existing == null ? new CsCallMetricDaily() : existing;
        row.setStatDate(from);
        row.setWorkspaceId(workspaceId);
        row.setCallCount(computed.getCallCount());
        row.setAnsweredCount(computed.getAnsweredCount());
        row.setAnswerRate(computed.getAnswerRate());
        row.setAvgDurationSec(computed.getAvgDurationSec());
        row.setBotResolveRate(computed.getBotResolveRate());
        row.setRecordingCoverage(computed.getRecordingCoverage());
        row.setUpdateTime(now);
        if (existing == null) {
            row.setCreateTime(now);
            metricMapper.insert(row);
        } else {
            metricMapper.updateById(row);
        }
        return row;
    }

    public CsCallMetricDaily today(Long workspaceId) {
        return rebuild(workspaceId, new Date());
    }

    /** 纯函数聚合,便于单测锁定口径。 */
    public static CsCallMetricDaily compute(List<com.forgeagent.callcenter.domain.CsCall> calls) {
        CsCallMetricDaily row = new CsCallMetricDaily();
        int total = calls.size();
        row.setCallCount(total);
        long answered = calls.stream()
            .filter(c -> c.getAnswerAt() != null).count();
        row.setAnsweredCount((int) answered);
        row.setAnswerRate(percent(total == 0 ? 0 : (double) answered / total));
        double avg = calls.stream()
            .filter(c -> c.getDurationSec() != null && c.getDurationSec() > 0)
            .mapToInt(com.forgeagent.callcenter.domain.CsCall::getDurationSec)
            .average().orElse(0);
        row.setAvgDurationSec((int) Math.round(avg));
        long botResolved = calls.stream()
            .filter(c -> c.getTicketId() == null).count();
        row.setBotResolveRate(percent(total == 0 ? 0 : (double) botResolved / total));
        long recorded = calls.stream()
            .filter(c -> c.getRecordingOssKey() != null && !c.getRecordingOssKey().isBlank()).count();
        row.setRecordingCoverage(percent(total == 0 ? 0 : (double) recorded / total));
        return row;
    }

    private static BigDecimal percent(double value) {
        return BigDecimal.valueOf(value * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private static Date dayStart(Date day) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(day);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}
