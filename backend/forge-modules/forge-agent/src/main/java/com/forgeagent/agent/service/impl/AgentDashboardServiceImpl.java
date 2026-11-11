package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agent.domain.AgentMarketListing;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.domain.AgentTokenUsage;
import com.forgeagent.agent.domain.AgentTokenUsageDaily;
import com.forgeagent.agent.domain.AgentTriggerLog;
import com.forgeagent.agent.domain.AgentWorkflowRun;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.vo.DashboardOverviewVo;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agent.mapper.AgentApprovalMapper;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agent.mapper.AgentMarketListingMapper;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.agent.mapper.AgentTokenUsageDailyMapper;
import com.forgeagent.agent.mapper.AgentTokenUsageMapper;
import com.forgeagent.agent.mapper.AgentTriggerLogMapper;
import com.forgeagent.agent.mapper.AgentWorkflowRunMapper;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agent.service.IAgentDashboardService;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运营看板 Service 实现
 * <p>单接口按角色裁剪 section,平台管理员取全平台口径,空间管理员取管辖空间口径。
 * 聚合结果按用户维度缓存 60s,避免看板频繁刷新带来的重复扫描。</p>
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentDashboardServiceImpl implements IAgentDashboardService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String STATUS_DRAFT = "0";
    private static final String STATUS_PUBLISHED = "1";
    private static final String STATUS_DISABLED = "2";

    private static final String APPROVAL_PENDING = "pending";
    private static final String RUN_FAIL = "fail";
    private static final String LISTING_PENDING = "0";

    /**
     * 趋势与排行统计窗口(天)
     */
    private static final int TREND_DAYS = 14;

    /**
     * 缓存有效期(秒)
     */
    private static final long CACHE_SECONDS = 60;

    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final AgentInfoMapper agentMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentTokenUsageMapper usageMapper;
    private final AgentTokenUsageDailyMapper dailyMapper;
    private final AgentApprovalMapper approvalMapper;
    private final AgentMarketListingMapper listingMapper;
    private final AgentWorkflowRunMapper workflowRunMapper;
    private final AgentTriggerLogMapper triggerLogMapper;

    @Override
    public DashboardOverviewVo overview() {
        Scope scope = resolveScope();
        String cacheKey = "agent:dashboard:overview:" + LoginHelper.getUserId() + ":" + scope.key();
        DashboardOverviewVo cached = RedisUtils.getCacheObject(cacheKey);
        if (cached != null) {
            return cached;
        }
        DashboardOverviewVo vo = build(scope);
        RedisUtils.setCacheObject(cacheKey, vo, Duration.ofSeconds(CACHE_SECONDS));
        return vo;
    }

    /**
     * 解析当前用户视角:平台管理员为全平台,否则为其任管理员的全部空间
     */
    private Scope resolveScope() {
        if (LoginHelper.isSuperAdmin()) {
            return new Scope(true, null, "platform");
        }
        Long userId = LoginHelper.getUserId();
        List<AgentWorkspaceMember> memberships = memberMapper.selectList(
            new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, userId)
                .eq(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode()));
        List<Long> wsIds = memberships.stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().sorted().toList();
        if (wsIds.isEmpty()) {
            throw new ServiceException("仅平台管理员或空间管理员可访问运营看板");
        }
        return new Scope(false, wsIds, wsCsv(wsIds));
    }

    private DashboardOverviewVo build(Scope scope) {
        DashboardOverviewVo vo = new DashboardOverviewVo();
        vo.setScope(scope.platform() ? "platform" : "workspace");
        vo.setDataEndTime(LocalDateTime.now().format(DT_FMT));
        vo.setKpi(buildKpi(scope));
        vo.setTrend(buildTrend(scope));
        vo.setAgentRank(buildAgentRank(scope));
        vo.setTodo(buildTodo(scope));
        vo.setFeedback(buildFeedback(scope));
        return vo;
    }

    /**
     * 消息反馈区:助手消息点赞/点踩计数与满意度,按会话口径裁剪
     */
    private DashboardOverviewVo.FeedbackSection buildFeedback(Scope scope) {
        DashboardOverviewVo.FeedbackSection section = new DashboardOverviewVo.FeedbackSection();
        long like = countFeedback("like", scope, null);
        long dislike = countFeedback("dislike", scope, null);
        section.setLikeCount(like);
        section.setDislikeCount(dislike);
        Date todayStart = java.sql.Timestamp.valueOf(LocalDate.now().atStartOfDay());
        section.setLikeCountToday(countFeedback("like", scope, todayStart));
        section.setDislikeCountToday(countFeedback("dislike", scope, todayStart));
        long total = like + dislike;
        section.setSatisfaction(total == 0 ? null : Math.round(like * 1000.0 / total) / 10.0);
        return section;
    }

    /**
     * 反馈计数辅助(feedback 为 like/dislike,sessionBegin 为空则统计全量)
     */
    private long countFeedback(String feedback, Scope scope, Date sessionBegin) {
        // "今日"口径按反馈发生时间(feedback_time)统计,历史消息今天点的赞也计入
        LambdaQueryWrapper<AgentMessage> wrapper = new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getRole, "assistant")
            .eq(AgentMessage::getFeedback, feedback)
            .ge(sessionBegin != null, AgentMessage::getFeedbackTime, sessionBegin);
        if (!scope.platform()) {
            wrapper.inSql(AgentMessage::getSessionId, sessionIdSql(scope));
        }
        return messageMapper.selectCount(wrapper);
    }

    /**
     * KPI 指标区
     */
    private DashboardOverviewVo.KpiSection buildKpi(Scope scope) {
        DashboardOverviewVo.KpiSection kpi = new DashboardOverviewVo.KpiSection();
        if (scope.platform()) {
            kpi.setWorkspaceCount(workspaceMapper.selectCount(null));
            kpi.setMemberCount(memberMapper.selectCount(null));
            kpi.setAgentCount(agentMapper.selectCount(null));
            kpi.setDraftCount(agentMapper.selectCount(agentStatusWrapper(null, STATUS_DRAFT)));
            kpi.setPublishedCount(agentMapper.selectCount(agentStatusWrapper(null, STATUS_PUBLISHED)));
            kpi.setDisabledCount(agentMapper.selectCount(agentStatusWrapper(null, STATUS_DISABLED)));
            kpi.setSessionCount(sessionMapper.selectCount(null));
            kpi.setMessageCount(messageMapper.selectCount(null));
        } else {
            // 空间视角无「平台工作空间总数」语义,置空由前端隐藏
            kpi.setMemberCount(memberMapper.selectCount(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .in(AgentWorkspaceMember::getWorkspaceId, scope.wsIds())));
            kpi.setAgentCount(agentMapper.selectCount(agentScopeWrapper(scope)));
            kpi.setDraftCount(agentMapper.selectCount(agentStatusWrapper(scope, STATUS_DRAFT)));
            kpi.setPublishedCount(agentMapper.selectCount(agentStatusWrapper(scope, STATUS_PUBLISHED)));
            kpi.setDisabledCount(agentMapper.selectCount(agentStatusWrapper(scope, STATUS_DISABLED)));
            kpi.setSessionCount(sessionMapper.selectCount(new LambdaQueryWrapper<AgentSession>()
                .inSql(AgentSession::getAgentId, agentIdSql(scope))));
            kpi.setMessageCount(messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>()
                .inSql(AgentMessage::getSessionId, sessionIdSql(scope))));
        }
        long[] sums = sumTokens(scope);
        kpi.setTotalTokens(sums[0]);
        kpi.setCallCount(sums[1]);
        return kpi;
    }

    /**
     * 近 14 天 Token 趋势(日汇总表补历史,明细表补当日)
     */
    private List<DashboardOverviewVo.TrendPoint> buildTrend(Scope scope) {
        LocalDate today = LocalDate.now();
        LocalDate begin = today.minusDays(TREND_DAYS - 1L);
        Map<String, DashboardOverviewVo.TrendPoint> map = new LinkedHashMap<>();
        for (LocalDate d = begin; !d.isAfter(today); d = d.plusDays(1)) {
            DashboardOverviewVo.TrendPoint point = new DashboardOverviewVo.TrendPoint();
            point.setStatDate(d.toString());
            point.setTotalTokens(0L);
            point.setCallCount(0L);
            map.put(d.toString(), point);
        }
        for (AgentTokenUsageDaily d : dailyMapper.selectList(dailyScoped(scope, begin, today))) {
            DashboardOverviewVo.TrendPoint point = map.get(d.getStatDate().toString());
            if (point != null) {
                point.setTotalTokens(point.getTotalTokens() + nz(d.getTotalTokens()));
                point.setCallCount(point.getCallCount() + nz(d.getCallCount()));
            }
        }
        for (AgentTokenUsage u : usageMapper.selectList(todayScoped(scope))) {
            if (u.getCallTime() == null) {
                continue;
            }
            DashboardOverviewVo.TrendPoint point = map.get(u.getCallTime().toLocalDate().toString());
            if (point != null) {
                point.setTotalTokens(point.getTotalTokens() + nz(u.getTotalTokens()));
                point.setCallCount(point.getCallCount() + 1);
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 近 14 天 Agent 用量排行(近 10 名)
     */
    private List<DashboardOverviewVo.RankItem> buildAgentRank(Scope scope) {
        LocalDate today = LocalDate.now();
        LocalDate begin = today.minusDays(TREND_DAYS - 1L);
        Map<Long, long[]> agg = new HashMap<>();
        for (AgentTokenUsageDaily d : dailyMapper.selectList(dailyScoped(scope, begin, today))) {
            agg.computeIfAbsent(d.getAgentId(), k -> new long[2])[0] += nz(d.getTotalTokens());
            agg.get(d.getAgentId())[1] += nz(d.getCallCount());
        }
        for (AgentTokenUsage u : usageMapper.selectList(todayScoped(scope))) {
            agg.computeIfAbsent(u.getAgentId(), k -> new long[2])[0] += nz(u.getTotalTokens());
            agg.get(u.getAgentId())[1] += 1;
        }
        long total = agg.values().stream().mapToLong(v -> v[0]).sum();
        Map<Long, String> nameCache = new HashMap<>();
        return agg.entrySet().stream().map(e -> {
                DashboardOverviewVo.RankItem item = new DashboardOverviewVo.RankItem();
                item.setName(resolveAgentName(e.getKey(), nameCache));
                item.setTotalTokens(e.getValue()[0]);
                item.setCallCount(e.getValue()[1]);
                item.setRatio(total == 0 ? 0D : Math.round(e.getValue()[0] * 10000D / total) / 100D);
                return item;
            }).sorted(Comparator.comparing(DashboardOverviewVo.RankItem::getTotalTokens).reversed())
            .limit(10).toList();
    }

    /**
     * 待办事项区(市场审核平台可见,其余两视角通用)
     */
    private DashboardOverviewVo.TodoSection buildTodo(Scope scope) {
        DashboardOverviewVo.TodoSection todo = new DashboardOverviewVo.TodoSection();
        if (scope.platform()) {
            todo.setReviewPendingCount(listingMapper.selectCount(new LambdaQueryWrapper<AgentMarketListing>()
                .eq(AgentMarketListing::getStatus, LISTING_PENDING)));
        }
        LambdaQueryWrapper<AgentApproval> approvalWrapper = new LambdaQueryWrapper<AgentApproval>()
            .eq(AgentApproval::getStatus, APPROVAL_PENDING);
        if (!scope.platform()) {
            approvalWrapper.inSql(AgentApproval::getAgentId, agentIdSql(scope));
        }
        todo.setApprovalPendingCount(approvalMapper.selectCount(approvalWrapper));

        LambdaQueryWrapper<AgentWorkflowRun> runWrapper = new LambdaQueryWrapper<AgentWorkflowRun>()
            .eq(AgentWorkflowRun::getStatus, RUN_FAIL);
        if (!scope.platform()) {
            runWrapper.in(AgentWorkflowRun::getWorkspaceId, scope.wsIds());
        }
        todo.setWorkflowFailCount(workflowRunMapper.selectCount(runWrapper));

        LambdaQueryWrapper<AgentTriggerLog> logWrapper = new LambdaQueryWrapper<AgentTriggerLog>()
            .eq(AgentTriggerLog::getStatus, RUN_FAIL);
        if (!scope.platform()) {
            logWrapper.inSql(AgentTriggerLog::getTriggerId,
                "select id from agent_trigger where del_flag = '0' and workspace_id in (" + wsCsv(scope.wsIds()) + ")");
        }
        todo.setTriggerFailCount(triggerLogMapper.selectCount(logWrapper));
        return todo;
    }

    /**
     * 累计 Token / 调用次数(日汇总全量 + 当日明细)
     */
    private long[] sumTokens(Scope scope) {
        long total = 0;
        long calls = 0;
        // 日汇总表实时 upsert 含当天行,此处须排除当天再叠加当日明细,避免双计
        LambdaQueryWrapper<AgentTokenUsageDaily> dailyWrapper = new LambdaQueryWrapper<AgentTokenUsageDaily>()
            .lt(AgentTokenUsageDaily::getStatDate, LocalDate.now());
        if (!scope.platform()) {
            dailyWrapper.in(AgentTokenUsageDaily::getWorkspaceId, scope.wsIds());
        }
        for (AgentTokenUsageDaily d : dailyMapper.selectList(dailyWrapper)) {
            total += nz(d.getTotalTokens());
            calls += nz(d.getCallCount());
        }
        for (AgentTokenUsage u : usageMapper.selectList(todayScoped(scope))) {
            total += nz(u.getTotalTokens());
            calls += 1;
        }
        return new long[]{total, calls};
    }

    private LambdaQueryWrapper<AgentTokenUsageDaily> dailyScoped(Scope scope, LocalDate begin, LocalDate today) {
        LambdaQueryWrapper<AgentTokenUsageDaily> wrapper = new LambdaQueryWrapper<AgentTokenUsageDaily>()
            .ge(AgentTokenUsageDaily::getStatDate, begin)
            .lt(AgentTokenUsageDaily::getStatDate, today);
        if (!scope.platform()) {
            wrapper.in(AgentTokenUsageDaily::getWorkspaceId, scope.wsIds());
        }
        return wrapper;
    }

    private LambdaQueryWrapper<AgentTokenUsage> todayScoped(Scope scope) {
        LambdaQueryWrapper<AgentTokenUsage> wrapper = new LambdaQueryWrapper<AgentTokenUsage>()
            .ge(AgentTokenUsage::getCallTime, LocalDate.now().atStartOfDay());
        if (!scope.platform()) {
            wrapper.in(AgentTokenUsage::getWorkspaceId, scope.wsIds());
        }
        return wrapper;
    }

    private LambdaQueryWrapper<AgentInfo> agentScopeWrapper(Scope scope) {
        LambdaQueryWrapper<AgentInfo> wrapper = new LambdaQueryWrapper<>();
        if (!scope.platform()) {
            wrapper.in(AgentInfo::getWorkspaceId, scope.wsIds());
        }
        return wrapper;
    }

    private LambdaQueryWrapper<AgentInfo> agentStatusWrapper(Scope scope, String status) {
        LambdaQueryWrapper<AgentInfo> wrapper = new LambdaQueryWrapper<AgentInfo>()
            .eq(AgentInfo::getStatus, status);
        if (scope != null && !scope.platform()) {
            wrapper.in(AgentInfo::getWorkspaceId, scope.wsIds());
        }
        return wrapper;
    }

    /**
     * 可见范围内 Agent ID 子查询(供 inSql 使用,须显式带 del_flag 条件)
     */
    private String agentIdSql(Scope scope) {
        if (scope.platform()) {
            return "select id from agent_info where del_flag = '0'";
        }
        return "select id from agent_info where del_flag = '0' and workspace_id in (" + wsCsv(scope.wsIds()) + ")";
    }

    /**
     * 可见范围内会话 ID 子查询
     */
    private String sessionIdSql(Scope scope) {
        return "select id from agent_session where del_flag = '0' and agent_id in (" + agentIdSql(scope) + ")";
    }

    private String wsCsv(List<Long> wsIds) {
        return wsIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String resolveAgentName(Long agentId, Map<Long, String> cache) {
        if (agentId == null) {
            return "未知Agent";
        }
        return cache.computeIfAbsent(agentId, id -> {
            AgentInfo agent = agentMapper.selectById(id);
            return agent == null ? ("Agent#" + id) : agent.getName();
        });
    }

    private long nz(Integer value) {
        return value == null ? 0L : value;
    }

    /**
     * 数据可见口径:平台=全平台,空间=当前用户任管理员的全部空间
     */
    private record Scope(boolean platform, List<Long> wsIds, String key) {
    }
}
