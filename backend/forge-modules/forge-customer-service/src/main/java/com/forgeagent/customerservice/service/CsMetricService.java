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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客服日指标(C2 / B3 补维):首响 P95 / 解决率 / 转人工率 / CSAT / SLA 达标率,
 * B3 追加重复进线率与 AI 自动解决率(containment),并按渠道拆行(全渠道行 channel_code 为 NULL)。
 *
 * <p>聚合走同步触发 + 手动补算 + 定时调度(CsMetricScheduler,默认关)三入口,
 * 不引入新调度框架;按 (stat_date, workspace_id, channel_code) 幂等 upsert。
 * 所有过滤条件下推到 SQL,不在应用层全表捞。</p>
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

    public List<com.forgeagent.customerservice.domain.vo.CsMetricDailyVo> list(Long workspaceId, Date from, Date to) {
        return toVoList(metricMapper.selectList(new LambdaQueryWrapper<CsMetricDaily>()
            .eq(CsMetricDaily::getWorkspaceId, workspaceId)
            .ge(from != null, CsMetricDaily::getStatDate, from)
            .le(to != null, CsMetricDaily::getStatDate, to)
            .orderByDesc(CsMetricDaily::getStatDate)));
    }

    /** 重建指定日期的全渠道 + 分渠道指标,重复执行覆盖同一行。 */
    public List<com.forgeagent.customerservice.domain.vo.CsMetricDailyVo> rebuild(Long workspaceId, Date statDate) {
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

        // B3:同访客 24h 内已有过进线的访客集合(查当日之前一个窗口,一次查出)
        Set<Long> visitorsWithPrior = visitorsWithPriorConversation(conversations, start);

        Map<String, List<CsConversation>> convByChannel = groupByChannel(conversations);
        Map<String, List<CsTicket>> ticketByChannel = groupTicketsByChannel(tickets);
        Map<String, List<CsCsat>> csatByChannel = groupCsatsByChannel(csats);

        List<CsMetricDaily> rows = new ArrayList<>();
        rows.add(buildRow(workspaceId, start, null, conversations, tickets, csats, visitorsWithPrior));
        for (Map.Entry<String, List<CsConversation>> entry : convByChannel.entrySet()) {
            rows.add(buildRow(workspaceId, start, entry.getKey(), entry.getValue(),
                ticketByChannel.getOrDefault(entry.getKey(), List.of()),
                csatByChannel.getOrDefault(entry.getKey(), List.of()),
                visitorsWithPrior));
        }
        for (CsMetricDaily row : rows) {
            upsertRow(row, start);
        }
        return toVoList(rows);
    }

    /** 实体→Vo 转换入口(protected:测试可覆写,规避 MapstructUtils 静态依赖 Spring 容器)。 */
    protected java.util.List<com.forgeagent.customerservice.domain.vo.CsMetricDailyVo> toVoList(java.util.List<CsMetricDaily> rows) {
        return com.forgeagent.common.core.utils.MapstructUtils.convert(rows, com.forgeagent.customerservice.domain.vo.CsMetricDailyVo.class);
    }

    /**
     * A4 服务漏斗:区间内会话三段真实计数,不复用 list() 的物化表(物化按 buildRow 逐日写,
     * 且当重建未跑时为空),直接对会话明细做一次范围查询,保证三段同源于 agent_cs_conversation。
     *
     * <p>口径与 containmentRate(CsMetricService:139-141)完全一致:未建单(ticket_id IS NULL)
     * = AI 自动解决,已建单 = 转人工。from/to 语义与 list() 对齐(from 当天 00:00、to 当天 00:00),
     * 但这里把 to 归一化到次日 00:00 做左闭右开,确保"选到某天"包含该天全天会话。</p>
     */
    public com.forgeagent.customerservice.domain.vo.CsFunnelVo funnel(Long workspaceId, Date from, Date to) {
        Date start = from == null ? null : dayRange(from)[0];
        Date end = to == null ? null : dayRange(to)[1];
        List<CsConversation> convs = conversationMapper.selectList(new LambdaQueryWrapper<CsConversation>()
            .eq(CsConversation::getWorkspaceId, workspaceId)
            .ge(start != null, CsConversation::getCreateTime, start)
            .lt(end != null, CsConversation::getCreateTime, end)
            .select(CsConversation::getId, CsConversation::getTicketId));
        long contained = convs.stream().filter(c -> c.getTicketId() == null).count();
        com.forgeagent.customerservice.domain.vo.CsFunnelVo vo =
            new com.forgeagent.customerservice.domain.vo.CsFunnelVo();
        vo.setSessions(convs.size());
        vo.setContained(contained);
        vo.setHandoff(convs.size() - contained);
        return vo;
    }

    private CsMetricDaily buildRow(Long workspaceId, Date statDate, String channelCode,
                                   List<CsConversation> conversations, List<CsTicket> tickets,
                                   List<CsCsat> csats, Set<Long> visitorsWithPrior) {
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

        CsMetricDaily metric = new CsMetricDaily();
        metric.setWorkspaceId(workspaceId);
        metric.setStatDate(statDate);
        metric.setChannelCode(channelCode);
        metric.setBotSessions(conversations.size());
        metric.setHandoffCount(tickets.size());
        metric.setFirstResponseP95Ms(percentile95(responseTimes));
        metric.setResolveRate(rate(resolved, tickets.size()));
        metric.setHandoffRate(rate(tickets.size(), conversations.size()));
        metric.setCsatAvg(scores.isEmpty() ? 0
            : Math.round(scores.stream().mapToLong(Long::longValue).average().orElse(0) * 100) / 100.0);
        metric.setCsatResponseRate(rate(submitted, csats.size()));
        metric.setSlaPassRate(rate(slaPass, responseTimes.size()));
        // B3:重复进线率——当日会话中,同访客 24h 内已有过进线的会话占比
        long repeatConversations = conversations.stream()
            .filter(c -> visitorsWithPrior.contains(c.getVisitorId())).count();
        metric.setRepeatContactRate(rate(repeatConversations, conversations.size()));
        // B3:AI 自动解决率——未产生转人工工单即终结的会话占比
        long contained = conversations.stream().filter(c -> c.getTicketId() == null).count();
        metric.setContainmentRate(rate(contained, conversations.size()));
        return metric;
    }

    private void upsertRow(CsMetricDaily metric, Date statDate) {
        String channelCode = metric.getChannelCode();
        CsMetricDaily existing = metricMapper.selectOne(new LambdaQueryWrapper<CsMetricDaily>()
            .eq(CsMetricDaily::getWorkspaceId, metric.getWorkspaceId())
            .eq(CsMetricDaily::getStatDate, statDate)
            .eq(channelCode != null, CsMetricDaily::getChannelCode, channelCode)
            .isNull(channelCode == null, CsMetricDaily::getChannelCode)
            .last("limit 1"));
        if (existing != null) {
            metric.setId(existing.getId());
            metricMapper.updateById(metric);
        } else {
            metricMapper.insert(metric);
        }
    }

    /** 同访客 24h 内已有过进线的访客集合:取当日访客,回查窗口内(当日之前)的会话。 */
    private Set<Long> visitorsWithPriorConversation(List<CsConversation> conversations, Date start) {
        if (conversations.isEmpty()) {
            return Set.of();
        }
        List<Long> visitorIds = conversations.stream()
            .map(CsConversation::getVisitorId).distinct().toList();
        Date windowStart = new Date(start.getTime() - CsTurnSignalService.REPEAT_WINDOW_MS);
        List<CsConversation> priors = conversationMapper.selectList(
            new LambdaQueryWrapper<CsConversation>()
                .in(CsConversation::getVisitorId, visitorIds)
                .lt(CsConversation::getCreateTime, start)
                .ge(CsConversation::getCreateTime, windowStart)
                .select(CsConversation::getVisitorId));
        Set<Long> withPrior = new HashSet<>();
        for (CsConversation prior : priors) {
            withPrior.add(prior.getVisitorId());
        }
        return withPrior;
    }

    private Map<String, List<CsConversation>> groupByChannel(List<CsConversation> conversations) {
        Map<String, List<CsConversation>> groups = new LinkedHashMap<>();
        for (CsConversation conversation : conversations) {
            groups.computeIfAbsent(channelKey(conversation.getChannelCode()), k -> new ArrayList<>())
                .add(conversation);
        }
        return groups;
    }

    private Map<String, List<CsTicket>> groupTicketsByChannel(List<CsTicket> tickets) {
        Map<String, List<CsTicket>> groups = new LinkedHashMap<>();
        for (CsTicket ticket : tickets) {
            groups.computeIfAbsent(channelKey(ticket.getChannelCode()), k -> new ArrayList<>())
                .add(ticket);
        }
        return groups;
    }

    private Map<String, List<CsCsat>> groupCsatsByChannel(List<CsCsat> csats) {
        Map<String, List<CsCsat>> groups = new LinkedHashMap<>();
        for (CsCsat csat : csats) {
            groups.computeIfAbsent(channelKey(csat.getChannelCode()), k -> new ArrayList<>())
                .add(csat);
        }
        return groups;
    }

    private static String channelKey(String channelCode) {
        return channelCode == null || channelCode.isBlank() ? "" : channelCode;
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
