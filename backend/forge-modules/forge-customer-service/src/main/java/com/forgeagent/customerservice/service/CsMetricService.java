package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsCsat;
import com.forgeagent.customerservice.domain.CsMetricDaily;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsCsatMapper;
import com.forgeagent.customerservice.mapper.CsMetricDailyMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 客服日指标(C2):首响 P95 / 解决率 / 转人工率 / CSAT / SLA 达标率。
 *
 * <p>聚合走同步触发 + 手动补算,不引入新调度框架;按 (stat_date, workspace_id)
 * 幂等 upsert。所有过滤条件下推到 SQL,不在应用层全表捞。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsMetricService {

    private final CsConversationMapper conversationMapper;
    private final CsTicketMapper ticketMapper;
    private final CsCsatMapper csatMapper;
    private final CsMetricDailyMapper metricMapper;
    private final CsModuleProperties properties;

    public List<CsMetricDaily> list(Long workspaceId, Date from, Date to) {
        return metricMapper.selectList(new LambdaQueryWrapper<CsMetricDaily>()
            .eq(CsMetricDaily::getWorkspaceId, workspaceId)
            .ge(from != null, CsMetricDaily::getStatDate, from)
            .le(to != null, CsMetricDaily::getStatDate, to)
            .orderByDesc(CsMetricDaily::getStatDate));
    }

    /** 重建指定日期的全渠道汇总指标,重复执行覆盖同一行。 */
    public CsMetricDaily rebuild(Long workspaceId, Date statDate) {
        Date[] range = dayRange(statDate);
        Date start = range[0];
        Date end = range[1];

        List<CsConversation> conversations = conversationMapper.selectList(
            new LambdaQueryWrapper<CsConversation>()
                .eq(CsConversation::getWorkspaceId, workspaceId)
                .ge(CsConversation::getCreateTime, start)
                .lt(CsConversation::getCreateTime, end));
        List<CsTicket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getWorkspaceId, workspaceId)
            .ge(CsTicket::getCreateTime, start)
            .lt(CsTicket::getCreateTime, end));
        List<CsCsat> csats = csatMapper.selectList(new LambdaQueryWrapper<CsCsat>()
            .eq(CsCsat::getWorkspaceId, workspaceId)
            .ge(CsCsat::getInvitedAt, start)
            .lt(CsCsat::getInvitedAt, end));

        long resolved = tickets.stream().filter(t ->
            "RESOLVED".equals(t.getStatus()) || "CLOSED".equals(t.getStatus())).count();
        List<Long> responseTimes = new ArrayList<>();
        long slaPass = 0;
        long slaThreshold = properties.getSlaFirstResponseMs() == null
            ? 60000L : properties.getSlaFirstResponseMs();
        for (CsTicket ticket : tickets) {
            if (ticket.getFirstResponseMs() != null) {
                responseTimes.add(ticket.getFirstResponseMs());
                if (ticket.getFirstResponseMs() <= slaThreshold) {
                    slaPass++;
                }
            }
        }
        List<Long> scores = csats.stream().filter(c -> c.getScore() != null)
            .map(c -> (long) c.getScore()).toList();
        long submitted = scores.size();

        CsMetricDaily metric = metricMapper.selectOne(new LambdaQueryWrapper<CsMetricDaily>()
            .eq(CsMetricDaily::getWorkspaceId, workspaceId)
            .eq(CsMetricDaily::getStatDate, start)
            .isNull(CsMetricDaily::getChannelCode).last("limit 1"));
        if (metric == null) {
            metric = new CsMetricDaily();
            metric.setWorkspaceId(workspaceId);
            metric.setStatDate(start);
        }
        metric.setBotSessions(conversations.size());
        metric.setHandoffCount(tickets.size());
        metric.setFirstResponseP95Ms(percentile95(responseTimes));
        metric.setResolveRate(rate(resolved, tickets.size()));
        metric.setHandoffRate(rate(tickets.size(), conversations.size()));
        metric.setCsatAvg(scores.isEmpty() ? 0
            : Math.round(scores.stream().mapToLong(Long::longValue).average().orElse(0) * 100) / 100.0);
        metric.setCsatResponseRate(rate(submitted, csats.size()));
        metric.setSlaPassRate(rate(slaPass, responseTimes.size()));
        if (metric.getId() == null) {
            metricMapper.insert(metric);
        } else {
            metricMapper.updateById(metric);
        }
        return metric;
    }

    private static long percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 100.0;
    }

    private static Date[] dayRange(Date statDate) {
        LocalDate day = statDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        ZoneId zone = ZoneId.systemDefault();
        return new Date[]{
            Date.from(day.atStartOfDay(zone).toInstant()),
            Date.from(day.plusDays(1).atStartOfDay(zone).toInstant())
        };
    }
}
