package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import com.forgeagent.callcenter.mapper.CsCallMetricDailyMapper;
import com.forgeagent.callcenter.mapper.CsCallOpsMetricMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 通话日指标(V2 → C8 扩维):挂机触发按 workspace + 自然日全量重算,幂等可对账。
 *
 * <p><b>口径</b>(全部可由当日通话行与两张关联表重算,不依赖历史累加):</p>
 * <ul>
 *   <li>通话总数 / 接通数 / 接通率 / 平均时长 / 机器人自助解决率 / 录音覆盖率 —— V2 既有;</li>
 *   <li>转人工数 / 率 —— {@code transferTo} 非空;</li>
 *   <li>呼损数 / 率 —— 有 {@code ringAt} 但无 {@code answerAt}(振铃未接通);</li>
 *   <li>平均等待时长 —— 已接通通话的 {@code answerAt - ringAt} 均值;</li>
 *   <li>负面通话数 / 率 —— C4 洞察表 SENTIMENT 标签 NEGATIVE 的按通话去重数(SQL 聚合下推);</li>
 *   <li>质检均分 —— 当日通话关联的语音质检分均值(0-100)。</li>
 * </ul>
 *
 * <p>重算幂等:同一天重跑只会覆盖同一行(唯一键 stat_date+workspace_id),
 * 数值完全由当日原始行推导,不存在"再累加一次"的问题,可直接用于对账。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallMetricService {

    private final CsCallMapper callMapper;
    private final CsCallMetricDailyMapper metricMapper;
    /** C8 跨表聚合;缺失(单测/未装配)时按 0 处理,不阻断主口径。 */
    private final ObjectProvider<CsCallOpsMetricMapper> opsMetricProvider;

    public CsCallMetricDaily rebuild(Long workspaceId, Date day) {
        Date from = dayStart(day);
        Date to = new Date(from.getTime() + 86_400_000L);
        List<CsCall> calls = callMapper.selectList(new LambdaQueryWrapper<CsCall>()
            .eq(CsCall::getWorkspaceId, workspaceId)
            .ge(CsCall::getRingAt, from)
            .lt(CsCall::getRingAt, to));
        CsCallMetricDaily computed = compute(calls, negativeCalls(workspaceId, from, to),
            qualityAvgScore(workspaceId, from, to));
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
        row.setTransferCount(computed.getTransferCount());
        row.setTransferRate(computed.getTransferRate());
        row.setAbandonCount(computed.getAbandonCount());
        row.setAbandonRate(computed.getAbandonRate());
        row.setAvgWaitSec(computed.getAvgWaitSec());
        row.setNegativeCalls(computed.getNegativeCalls());
        row.setNegativeRate(computed.getNegativeRate());
        row.setQualityAvgScore(computed.getQualityAvgScore());
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

    /** 兼容旧调用:跨表口径按 0 计。 */
    public static CsCallMetricDaily compute(List<CsCall> calls) {
        return compute(calls, 0, null);
    }

    /** 纯函数聚合,便于单测锁定口径(跨表数据由调用方传入)。 */
    public static CsCallMetricDaily compute(List<CsCall> calls, int negativeCalls, Double qualityAvgScore) {
        CsCallMetricDaily row = new CsCallMetricDaily();
        int total = calls.size();
        row.setCallCount(total);
        long answered = calls.stream().filter(c -> c.getAnswerAt() != null).count();
        row.setAnsweredCount((int) answered);
        row.setAnswerRate(percent(total == 0 ? 0 : (double) answered / total));
        double avg = calls.stream()
            .filter(c -> c.getDurationSec() != null && c.getDurationSec() > 0)
            .mapToInt(CsCall::getDurationSec)
            .average().orElse(0);
        row.setAvgDurationSec((int) Math.round(avg));
        long botResolved = calls.stream().filter(c -> c.getTicketId() == null).count();
        row.setBotResolveRate(percent(total == 0 ? 0 : (double) botResolved / total));
        long recorded = calls.stream()
            .filter(c -> c.getRecordingOssKey() != null && !c.getRecordingOssKey().isBlank()).count();
        row.setRecordingCoverage(percent(total == 0 ? 0 : (double) recorded / total));

        // C8:转人工
        long transferred = calls.stream()
            .filter(c -> c.getTransferTo() != null && !c.getTransferTo().isBlank()).count();
        row.setTransferCount((int) transferred);
        row.setTransferRate(percent(total == 0 ? 0 : (double) transferred / total));

        // C8:呼损(振铃未接通)——必须有 ringAt 才可能是呼损,无 ringAt 的脏数据不计
        long abandoned = calls.stream()
            .filter(c -> c.getRingAt() != null && c.getAnswerAt() == null).count();
        row.setAbandonCount((int) abandoned);
        row.setAbandonRate(percent(total == 0 ? 0 : (double) abandoned / total));

        // C8:平均等待时长(只统计已接通且有振铃时间)
        double avgWait = calls.stream()
            .filter(c -> c.getRingAt() != null && c.getAnswerAt() != null)
            .mapToLong(c -> Math.max(0L, c.getAnswerAt().getTime() - c.getRingAt().getTime()))
            .average().orElse(0) / 1000d;
        row.setAvgWaitSec((int) Math.round(avgWait));

        // C8:负面情绪通话
        row.setNegativeCalls(negativeCalls);
        row.setNegativeRate(percent(total == 0 ? 0 : (double) negativeCalls / total));

        // C8:质检均分(无记录记 0,便于前端直接展示;是否"无数据"由前端看 negative/quality 组合判断)
        row.setQualityAvgScore(scale(qualityAvgScore == null ? 0 : qualityAvgScore));
        return row;
    }

    private int negativeCalls(Long workspaceId, Date from, Date to) {
        CsCallOpsMetricMapper mapper = opsMetricProvider.getIfAvailable();
        if (mapper == null) {
            return 0;
        }
        try {
            Integer value = mapper.countNegativeCalls(workspaceId, from, to);
            return value == null ? 0 : value;
        } catch (Exception e) {
            // 洞察表不可用只影响这一个口径,不影响整行指标
            log.warn("[CC] 负面通话数聚合失败 ws={}: {}", workspaceId, e.getMessage());
            return 0;
        }
    }

    private Double qualityAvgScore(Long workspaceId, Date from, Date to) {
        CsCallOpsMetricMapper mapper = opsMetricProvider.getIfAvailable();
        if (mapper == null) {
            return null;
        }
        try {
            return mapper.avgVoiceQuality(workspaceId, from, to);
        } catch (Exception e) {
            log.warn("[CC] 质检均分聚合失败 ws={}: {}", workspaceId, e.getMessage());
            return null;
        }
    }

    private static BigDecimal percent(double value) {
        return BigDecimal.valueOf(value * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
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
