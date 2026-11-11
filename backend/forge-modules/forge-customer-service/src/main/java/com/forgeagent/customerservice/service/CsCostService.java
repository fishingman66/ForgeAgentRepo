package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agent.domain.AgentTokenUsage;
import com.forgeagent.agent.mapper.AgentTokenUsageMapper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsCostDaily;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsCostDailyMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话级成本口径(P2,差距分析 P2 第一项)。
 *
 * <p>平台已有 {@code agent_token_usage} 逐调用计量,但回答不了「这通客服会话花了多少」。
 * 这里按「客服会话的 agent_session_id」把 token 归集到日维度,再给两个业务单位:</p>
 * <ul>
 *   <li><b>每会话 token</b>:会话数做分母,看单次接待的成本;</li>
 *   <li><b>每次解决 token</b>:当日结案工单做分母,看「解决一个问题」的成本。</li>
 * </ul>
 *
 * <p>金额只在配置了 {@code cost-per-1k-tokens} 时计算;没配单价就只报 token,
 * 不写 0——假成本比没成本更危险(会让人以为不用花钱)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsCostService {

    private final CsCostDailyMapper costMapper;
    private final CsConversationMapper conversationMapper;
    private final CsTicketMapper ticketMapper;
    private final AgentTokenUsageMapper tokenUsageMapper;
    private final CsModuleProperties properties;

    /** 重算某日成本(幂等 upsert);看板打开或手动刷新时调用。 */
    public CsCostDaily rebuild(Long workspaceId, Date statDate) {
        Date[] range = dayRange(statDate);
        List<Long> sessionIds = conversationMapper.selectList(new LambdaQueryWrapper<CsConversation>()
                .eq(CsConversation::getWorkspaceId, workspaceId)
                .ge(CsConversation::getCreateTime, range[0])
                .lt(CsConversation::getCreateTime, range[1]))
            .stream().map(CsConversation::getAgentSessionId).filter(Objects::nonNull).distinct().toList();

        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        int callCount = 0;
        int estimatedCount = 0;
        int conversationCount = 0;
        if (!sessionIds.isEmpty()) {
            List<AgentTokenUsage> rows = tokenUsageMapper.selectList(
                new LambdaQueryWrapper<AgentTokenUsage>()
                    .in(AgentTokenUsage::getSessionId, sessionIds)
                    .ge(AgentTokenUsage::getCallTime, toLocal(range[0]))
                    .lt(AgentTokenUsage::getCallTime, toLocal(range[1])));
            for (AgentTokenUsage row : rows) {
                callCount++;
                promptTokens += nz(row.getPromptTokens());
                completionTokens += nz(row.getCompletionTokens());
                totalTokens += nz(row.getTotalTokens());
                if ("1".equals(row.getEstimated())) {
                    estimatedCount++;
                }
            }
            conversationCount = (int) rows.stream()
                .map(AgentTokenUsage::getSessionId).filter(Objects::nonNull).distinct().count();
        }

        Integer resolved = countResolved(workspaceId, range);
        BigDecimal costAmount = costOf(totalTokens);

        CsCostDaily row = costMapper.selectOne(new LambdaQueryWrapper<CsCostDaily>()
            .eq(CsCostDaily::getWorkspaceId, workspaceId)
            .eq(CsCostDaily::getStatDate, range[0]).last("limit 1"));
        boolean insert = row == null;
        if (insert) {
            row = new CsCostDaily();
            row.setWorkspaceId(workspaceId);
            row.setStatDate(range[0]);
            row.setCreateTime(new Date());
        }
        row.setConversationCount(conversationCount);
        row.setCallCount(callCount);
        row.setPromptTokens(promptTokens);
        row.setCompletionTokens(completionTokens);
        row.setTotalTokens(totalTokens);
        row.setEstimatedCount(estimatedCount);
        row.setCostAmount(costAmount);
        row.setCurrency(costAmount == null ? null : properties.getCostCurrency());
        row.setResolvedCount(resolved);
        row.setUpdateTime(new Date());
        if (insert) {
            costMapper.insert(row);
        } else {
            costMapper.updateById(row);
        }
        return row;
    }

    /** 看板数据:最近 N 天日成本 + 汇总 + 两个业务单位成本。 */
    public Map<String, Object> overview(Long workspaceId, int days) {
        int span = Math.max(1, Math.min(90, days));
        List<CsCostDaily> rows = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -i);
            rows.add(rebuild(workspaceId, cal.getTime()));
        }
        long totalTokens = rows.stream().mapToLong(r -> nzLong(r.getTotalTokens())).sum();
        long calls = rows.stream().mapToLong(r -> nzLong(r.getCallCount())).sum();
        long conversations = rows.stream().mapToLong(r -> nzLong(r.getConversationCount())).sum();
        long resolved = rows.stream().mapToLong(r -> nzLong(r.getResolvedCount())).sum();
        BigDecimal amount = sumAmount(rows);

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("totalTokens", totalTokens);
        total.put("callCount", calls);
        total.put("conversationCount", conversations);
        total.put("resolvedCount", resolved);
        total.put("tokensPerConversation", rate(totalTokens, conversations));
        total.put("tokensPerResolved", rate(totalTokens, resolved));
        total.put("costAmount", amount);
        total.put("amountAvailable", amount != null);
        total.put("currency", properties.getCostCurrency());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", rows);
        data.put("total", total);
        data.put("priceNote", amount == null
            ? "未配置 cost-per-1k-tokens，只统计 token 不折算金额" : "金额按配置单价折算，仅供参考");
        return data;
    }

    /** 当日已用 token(配额判定用):按空间维度汇总,口径含该空间全部模型调用。 */
    public long usedToday(Long workspaceId) {
        Date[] range = dayRange(new Date());
        List<AgentTokenUsage> rows = tokenUsageMapper.selectList(
            new LambdaQueryWrapper<AgentTokenUsage>()
                .eq(AgentTokenUsage::getWorkspaceId, workspaceId)
                .ge(AgentTokenUsage::getCallTime, toLocal(range[0]))
                .lt(AgentTokenUsage::getCallTime, toLocal(range[1]))
                .select(AgentTokenUsage::getTotalTokens));
        long sum = 0;
        for (AgentTokenUsage row : rows) {
            sum += nz(row.getTotalTokens());
        }
        return sum;
    }

    private Integer countResolved(Long workspaceId, Date[] range) {
        return Math.toIntExact(ticketMapper.selectCount(new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getWorkspaceId, workspaceId)
            .in(CsTicket::getStatus, "RESOLVED", "CLOSED")
            .ge(CsTicket::getResolvedAt, range[0])
            .lt(CsTicket::getResolvedAt, range[1])));
    }

    /** 金额:未配单价返回 null(宁可不报,不写 0)。 */
    private BigDecimal costOf(long totalTokens) {
        return costOf(totalTokens, properties.getCostPer1kTokens());
    }

    /** 金额换算(纯函数,单测锁定):单价缺失或为 0 时返回 null,绝不返回 0 冒充"免费"。 */
    public static BigDecimal costOf(long totalTokens, Double pricePer1k) {
        if (pricePer1k == null || pricePer1k <= 0 || totalTokens <= 0) {
            return null;
        }
        return BigDecimal.valueOf(totalTokens)
            .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(pricePer1k))
            .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal sumAmount(List<CsCostDaily> rows) {
        BigDecimal sum = null;
        for (CsCostDaily row : rows) {
            if (row.getCostAmount() != null) {
                sum = sum == null ? row.getCostAmount() : sum.add(row.getCostAmount());
            }
        }
        return sum;
    }

    /** 单位成本:分母为 0 时返回 0,前端显示「—」。 */
    private static long rate(long numerator, long denominator) {
        return denominator <= 0 ? 0 : Math.round((double) numerator / denominator);
    }

    private static long nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nzLong(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private static LocalDateTime toLocal(Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    /** 当天 00:00 与次日 00:00。 */
    private static Date[] dayRange(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date start = cal.getTime();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        return new Date[]{start, cal.getTime()};
    }
}
