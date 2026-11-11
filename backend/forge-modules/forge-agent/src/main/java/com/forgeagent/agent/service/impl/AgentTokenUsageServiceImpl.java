package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.domain.AgentTokenUsage;
import com.forgeagent.agent.domain.AgentTokenUsageDaily;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentTokenUsageBo;
import com.forgeagent.agent.domain.vo.AgentSessionUsageBarVo;
import com.forgeagent.agent.domain.vo.AgentSessionUsageVo;
import com.forgeagent.agent.domain.vo.AgentTokenUsageVo;
import com.forgeagent.agent.domain.vo.AgentUsageReportVo;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.agent.mapper.AgentTokenUsageDailyMapper;
import com.forgeagent.agent.mapper.AgentTokenUsageMapper;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agent.service.IAgentTokenUsageService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.excel.utils.ExcelBuilder;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Token 用量统计 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentTokenUsageServiceImpl implements IAgentTokenUsageService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 会话用量聚合缓存前缀(UX-03),落库后主动失效 */
    private static final String SESSION_AGG_CACHE_PREFIX = "agent:session:usage:";
    /** 会话用量聚合缓存 TTL(秒) */
    private static final long SESSION_AGG_CACHE_SECONDS = 15L;

    private final AgentTokenUsageMapper usageMapper;
    private final AgentTokenUsageDailyMapper dailyMapper;
    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentInfoMapper agentMapper;
    private final AgentConfigCacheService agentConfigCache;

    @Override
    public AgentUsageReportVo report(AgentTokenUsageBo bo) {
        LocalDate today = LocalDate.now();
        LocalDate begin = parseDate(bo.getBeginTime(), today.minusDays(6));
        LocalDate end = parseDate(bo.getEndTime(), today);

        // 1. 日汇总(今日之前) + 今日明细 两类数据源,均受可见范围约束
        List<AgentTokenUsageDaily> dailyRows = dailyMapper.selectList(
            applyDailyVisibility(new LambdaQueryWrapper<AgentTokenUsageDaily>(), bo)
                .ge(AgentTokenUsageDaily::getStatDate, begin)
                .lt(AgentTokenUsageDaily::getStatDate, today)
                .le(AgentTokenUsageDaily::getStatDate, end));
        List<AgentTokenUsage> todayRows = usageMapper.selectList(
            applyUsageVisibility(new LambdaQueryWrapper<AgentTokenUsage>(), bo)
                .ge(AgentTokenUsage::getCallTime, today.atStartOfDay())
                .le(AgentTokenUsage::getCallTime, end.plusDays(1).atStartOfDay().minusSeconds(1)));

        // 2. 指标卡
        AgentUsageReportVo vo = new AgentUsageReportVo();
        long prompt = 0, completion = 0, total = 0, calls = 0, estTokens = 0;
        for (AgentTokenUsageDaily d : dailyRows) {
            prompt += nz(d.getPromptTokens());
            completion += nz(d.getCompletionTokens());
            total += nz(d.getTotalTokens());
            calls += nz(d.getCallCount());
            if ("1".equals(d.getEstimated())) {
                estTokens += nz(d.getTotalTokens());
            }
        }
        for (AgentTokenUsage u : todayRows) {
            prompt += nz(u.getPromptTokens());
            completion += nz(u.getCompletionTokens());
            total += nz(u.getTotalTokens());
            calls += 1;
            if ("1".equals(u.getEstimated())) {
                estTokens += nz(u.getTotalTokens());
            }
        }
        vo.setPromptTokens(prompt);
        vo.setCompletionTokens(completion);
        vo.setTotalTokens(total);
        vo.setCallCount(calls);
        vo.setEstimatedRatio(total == 0 ? 0D : Math.round(estTokens * 10000D / total) / 100D);
        vo.setDataEndTime(LocalDateTime.now().format(DT_FMT));

        // 3. 按日趋势
        Map<String, AgentUsageReportVo.DailyItem> trendMap = new LinkedHashMap<>();
        for (LocalDate d = begin; !d.isAfter(end); d = d.plusDays(1)) {
            AgentUsageReportVo.DailyItem item = new AgentUsageReportVo.DailyItem();
            item.setStatDate(d.toString());
            item.setPromptTokens(0L);
            item.setCompletionTokens(0L);
            item.setTotalTokens(0L);
            item.setCallCount(0L);
            trendMap.put(d.toString(), item);
        }
        for (AgentTokenUsageDaily d : dailyRows) {
            AgentUsageReportVo.DailyItem item = trendMap.get(d.getStatDate().toString());
            if (item != null) {
                item.setPromptTokens(item.getPromptTokens() + nz(d.getPromptTokens()));
                item.setCompletionTokens(item.getCompletionTokens() + nz(d.getCompletionTokens()));
                item.setTotalTokens(item.getTotalTokens() + nz(d.getTotalTokens()));
                item.setCallCount(item.getCallCount() + nz(d.getCallCount()));
            }
        }
        for (AgentTokenUsage u : todayRows) {
            if (u.getCallTime() == null) {
                continue;
            }
            AgentUsageReportVo.DailyItem item = trendMap.get(u.getCallTime().toLocalDate().toString());
            if (item != null) {
                item.setPromptTokens(item.getPromptTokens() + nz(u.getPromptTokens()));
                item.setCompletionTokens(item.getCompletionTokens() + nz(u.getCompletionTokens()));
                item.setTotalTokens(item.getTotalTokens() + nz(u.getTotalTokens()));
                item.setCallCount(item.getCallCount() + 1);
            }
        }
        vo.setDailyTrend(new ArrayList<>(trendMap.values()));

        // 4. 排行(按 Agent / 按模型) —— 先批量查所有 Agent 名称,避免循环中逐条 selectById
        List<Long> allAgentIds = new ArrayList<>();
        dailyRows.forEach(d -> { if (d.getAgentId() != null) allAgentIds.add(d.getAgentId()); });
        todayRows.forEach(u -> { if (u.getAgentId() != null) allAgentIds.add(u.getAgentId()); });
        Map<Long, String> agentNameCache = allAgentIds.isEmpty() ? Map.of()
            : agentMapper.selectByIds(allAgentIds.stream().distinct().toList())
                .stream().collect(Collectors.toMap(AgentInfo::getId, a -> StringUtils.defaultIfBlank(a.getName(), ""), (a, b) -> a));

        Map<String, long[]> agentAgg = new HashMap<>();
        Map<String, long[]> modelAgg = new HashMap<>();
        Consumer<AgentTokenUsageDaily> dailyAgg = d -> {
            String agentName = d.getAgentId() == null ? "未知Agent"
                : StringUtils.isBlank(agentNameCache.get(d.getAgentId()))
                    ? ("Agent#" + d.getAgentId()) : agentNameCache.get(d.getAgentId());
            agentAgg.computeIfAbsent(agentName, k -> new long[2])[0] += nz(d.getTotalTokens());
            agentAgg.get(agentName)[1] += nz(d.getCallCount());
            String model = StringUtils.isBlank(d.getModelName()) ? "未指定" : d.getModelName();
            modelAgg.computeIfAbsent(model, k -> new long[2])[0] += nz(d.getTotalTokens());
            modelAgg.get(model)[1] += nz(d.getCallCount());
        };
        dailyRows.forEach(dailyAgg);
        for (AgentTokenUsage u : todayRows) {
            String agentName = u.getAgentId() == null ? "未知Agent"
                : StringUtils.isBlank(agentNameCache.get(u.getAgentId()))
                    ? ("Agent#" + u.getAgentId()) : agentNameCache.get(u.getAgentId());
            agentAgg.computeIfAbsent(agentName, k -> new long[2])[0] += nz(u.getTotalTokens());
            agentAgg.get(agentName)[1] += 1;
            String model = StringUtils.isBlank(u.getModelName()) ? "未指定" : u.getModelName();
            modelAgg.computeIfAbsent(model, k -> new long[2])[0] += nz(u.getTotalTokens());
            modelAgg.get(model)[1] += 1;
        }
        vo.setAgentRank(buildRank(agentAgg, total));
        vo.setModelRank(buildRank(modelAgg, total));
        return vo;
    }

    @Override
    public PageResult<AgentTokenUsageVo> queryPageList(AgentTokenUsageBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentTokenUsage> wrapper = applyUsageVisibility(new LambdaQueryWrapper<>(), bo)
            .eq(bo.getSessionId() != null, AgentTokenUsage::getSessionId, bo.getSessionId())
            .ge(StringUtils.isNotBlank(bo.getBeginTime()), AgentTokenUsage::getCallTime, bo.getBeginTime())
            .le(StringUtils.isNotBlank(bo.getEndTime()), AgentTokenUsage::getCallTime, bo.getEndTime())
            .orderByDesc(AgentTokenUsage::getId);
        Page<AgentTokenUsageVo> page = usageMapper.selectVoPage(pageQuery.build(), wrapper);
        fillNames(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentSessionUsageVo sessionUsage(Long sessionId) {
        AgentSession session = checkSessionAccess(sessionId);
        List<AgentTokenUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<AgentTokenUsage>()
            .eq(AgentTokenUsage::getSessionId, sessionId));
        AgentSessionUsageVo vo = new AgentSessionUsageVo();
        vo.setSessionId(sessionId);
        long prompt = 0, completion = 0, total = 0;
        for (AgentTokenUsage u : rows) {
            prompt += nz(u.getPromptTokens());
            completion += nz(u.getCompletionTokens());
            total += nz(u.getTotalTokens());
        }
        vo.setPromptTokens(prompt);
        vo.setCompletionTokens(completion);
        vo.setTotalTokens(total);
        vo.setCallCount((long) rows.size());
        vo.setTokenBudget(0L);
        vo.setBudgetUsage(null);
        return vo;
    }

    /**
     * 会话用量实时反馈(UX-03):聚合结果 15s Redis 短缓存,落库后由 TokenUsageRecorder 主动失效
     */
    @Override
    public AgentSessionUsageBarVo sessionUsageBar(Long sessionId) {
        AgentSession session = checkSessionAccess(sessionId);
        AgentSessionUsageBarVo vo = new AgentSessionUsageBarVo();
        vo.setSessionId(sessionId);

        // 1. 聚合(累计输入/输出/总量 + 最近一轮输入/总量),15s 缓存
        long[] agg = loadSessionAgg(sessionId);
        vo.setPromptTokens(agg[0]);
        vo.setCompletionTokens(agg[1]);
        vo.setTotalTokens(agg[2]);
        long lastRoundPrompt = agg[3];
        vo.setLastRoundTokens(agg[4]);

        // 2. 上下文剩余:按会话 Agent 绑定模型的 contextWindow 计算,未配置则不返回
        AgentModel model = resolveSessionModel(session);
        if (model != null && model.getContextWindow() != null && model.getContextWindow() > 0) {
            int window = model.getContextWindow();
            vo.setContextWindow(window);
            // 以最近一轮输入 tokens 近似当前上下文占用(含系统提示+历史+召回+本轮输入)
            long used = Math.min(lastRoundPrompt, window);
            vo.setContextUsed(used);
            double remaining = Math.max(0D, (window - used) * 100D / window);
            vo.setContextRemainingPercent(Math.round(remaining * 10D) / 10D);
        }

        // 3. 成本估算:模型配置定价时返回
        if (model != null && model.getPriceInput() != null && model.getPriceOutput() != null) {
            java.math.BigDecimal cost = model.getPriceInput()
                .multiply(java.math.BigDecimal.valueOf(agg[0]))
                .add(model.getPriceOutput().multiply(java.math.BigDecimal.valueOf(agg[1])))
                .divide(java.math.BigDecimal.valueOf(1_000_000L), 6, java.math.RoundingMode.HALF_UP);
            vo.setCostEstimate(cost);
            vo.setCurrency(StringUtils.defaultIfBlank(model.getPriceCurrency(), "USD"));
        }
        return vo;
    }

    /**
     * 会话用量聚合缓存 key(与 TokenUsageRecorder 落库后失效保持一致)
     */
    public static String sessionAggCacheKey(Long sessionId) {
        return SESSION_AGG_CACHE_PREFIX + sessionId;
    }

    /**
     * 加载会话聚合用量:优先 15s 缓存,miss 时一次 SUM 聚合 + 最近一轮明细
     *
     * @return long[5]: totalPrompt, totalCompletion, total, lastRoundPrompt, lastRoundTotal
     */
    private long[] loadSessionAgg(Long sessionId) {
        String cacheKey = sessionAggCacheKey(sessionId);
        try {
            String cached = com.forgeagent.common.redis.utils.RedisUtils.getCacheObject(cacheKey);
            if (StringUtils.isNotBlank(cached)) {
                String[] parts = cached.split(",");
                if (parts.length == 5) {
                    long[] arr = new long[5];
                    for (int i = 0; i < 5; i++) {
                        arr[i] = Long.parseLong(parts[i]);
                    }
                    return arr;
                }
            }
        } catch (Exception ignore) {
            // 缓存不可用时直接查库
        }
        long[] arr = new long[5];
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AgentTokenUsage> sumQw =
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        sumQw.select("IFNULL(SUM(prompt_tokens),0) AS p", "IFNULL(SUM(completion_tokens),0) AS c",
            "IFNULL(SUM(total_tokens),0) AS t").eq("session_id", sessionId);
        java.util.Map<String, Object> sumRow = usageMapper.selectMaps(sumQw).stream().findFirst().orElse(null);
        if (sumRow != null) {
            arr[0] = ((Number) sumRow.getOrDefault("p", 0)).longValue();
            arr[1] = ((Number) sumRow.getOrDefault("c", 0)).longValue();
            arr[2] = ((Number) sumRow.getOrDefault("t", 0)).longValue();
        }
        AgentTokenUsage last = usageMapper.selectOne(new LambdaQueryWrapper<AgentTokenUsage>()
            .eq(AgentTokenUsage::getSessionId, sessionId)
            .orderByDesc(AgentTokenUsage::getId)
            .last("limit 1"));
        if (last != null) {
            arr[3] = nz(last.getPromptTokens());
            arr[4] = nz(last.getTotalTokens());
        }
        try {
            com.forgeagent.common.redis.utils.RedisUtils.setCacheObject(cacheKey,
                arr[0] + "," + arr[1] + "," + arr[2] + "," + arr[3] + "," + arr[4],
                java.time.Duration.ofSeconds(SESSION_AGG_CACHE_SECONDS));
        } catch (Exception ignore) {
            // 缓存写入失败不影响查询
        }
        return arr;
    }

    /**
     * 解析会话绑定模型(经 60s 配置缓存);会话/Agent 无模型时返回 null
     */
    private AgentModel resolveSessionModel(AgentSession session) {
        if (session.getAgentId() == null) {
            return null;
        }
        AgentInfo agent = agentConfigCache.getAgentById(session.getAgentId());
        if (agent == null || agent.getModelId() == null) {
            return null;
        }
        return agentConfigCache.getModelById(agent.getModelId());
    }

    /**
     * 会话可见性校验:本人会话直接放行;非本人需为会话 Agent 所属空间的管理员(超管自动放行)
     */
    private AgentSession checkSessionAccess(Long sessionId) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ServiceException("会话不存在");
        }
        Long userId = LoginHelper.getUserId();
        if (!LoginHelper.isSuperAdmin() && !session.getUserId().equals(userId)) {
            // 非本人会话需为空间管理员
            AgentInfo agent = agentMapper.selectById(session.getAgentId());
            if (agent == null) {
                throw new ServiceException("无权查看该会话用量");
            }
            Long count = memberMapper.selectCount(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getWorkspaceId, agent.getWorkspaceId())
                .eq(AgentWorkspaceMember::getUserId, userId)
                .eq(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode()));
            if (count == null || count == 0) {
                throw new ServiceException("无权查看该会话用量");
            }
        }
        return session;
    }

    @Override
    public void export(AgentTokenUsageBo bo, HttpServletResponse response) {
        LambdaQueryWrapper<AgentTokenUsage> wrapper = applyUsageVisibility(new LambdaQueryWrapper<>(), bo)
            .eq(bo.getSessionId() != null, AgentTokenUsage::getSessionId, bo.getSessionId())
            .ge(StringUtils.isNotBlank(bo.getBeginTime()), AgentTokenUsage::getCallTime, bo.getBeginTime())
            .le(StringUtils.isNotBlank(bo.getEndTime()), AgentTokenUsage::getCallTime, bo.getEndTime())
            .orderByDesc(AgentTokenUsage::getId)
            .last("limit 10000");
        List<AgentTokenUsageVo> list = usageMapper.selectVoList(wrapper);
        fillNames(list);
        ExcelBuilder.of(list, AgentTokenUsageVo.class).sheetName("Token用量明细").toResponse(response);
    }

    /**
     * 明细可见范围(FR-M7-04): 超管全量; 空间管理员全空间; 开发者/运行者仅本人
     * <p>deptId 为平台管理视角的部门过滤,与上述可见性叠加(取交集)</p>
     */
    private LambdaQueryWrapper<AgentTokenUsage> applyUsageVisibility(LambdaQueryWrapper<AgentTokenUsage> w, AgentTokenUsageBo bo) {
        w.eq(bo.getAgentId() != null, AgentTokenUsage::getAgentId, bo.getAgentId())
            .like(StringUtils.isNotBlank(bo.getModelName()), AgentTokenUsage::getModelName, bo.getModelName())
            .eq(StringUtils.isNotBlank(bo.getSource()), AgentTokenUsage::getSource, bo.getSource())
            .eq(StringUtils.isNotBlank(bo.getEstimated()), AgentTokenUsage::getEstimated, bo.getEstimated());
        List<Long> deptWs = deptWorkspaceIds(bo.getDeptId());
        if (deptWs != null) {
            if (deptWs.isEmpty()) {
                w.eq(AgentTokenUsage::getWorkspaceId, -1L);
            } else {
                w.in(AgentTokenUsage::getWorkspaceId, deptWs);
            }
        }
        if (LoginHelper.isSuperAdmin()) {
            w.eq(bo.getWorkspaceId() != null, AgentTokenUsage::getWorkspaceId, bo.getWorkspaceId())
                .eq(bo.getUserId() != null, AgentTokenUsage::getUserId, bo.getUserId());
            return w;
        }
        Long userId = LoginHelper.getUserId();
        if (bo.getWorkspaceId() != null) {
            String role = memberRole(bo.getWorkspaceId(), userId);
            if (role == null) {
                throw new ServiceException("您不是该工作空间成员");
            }
            w.eq(AgentTokenUsage::getWorkspaceId, bo.getWorkspaceId());
            if (!WorkspaceRole.ADMIN.getCode().equals(role)) {
                w.eq(AgentTokenUsage::getUserId, userId);
            } else {
                w.eq(bo.getUserId() != null, AgentTokenUsage::getUserId, bo.getUserId());
            }
            return w;
        }
        List<AgentWorkspaceMember> memberships = memberMapper.selectList(
            new LambdaQueryWrapper<AgentWorkspaceMember>().eq(AgentWorkspaceMember::getUserId, userId));
        if (memberships.isEmpty()) {
            w.eq(AgentTokenUsage::getUserId, userId);
            return w;
        }
        List<Long> adminWs = memberships.stream()
            .filter(m -> WorkspaceRole.ADMIN.getCode().equals(m.getRole()))
            .map(AgentWorkspaceMember::getWorkspaceId).toList();
        List<Long> memberWs = memberships.stream().map(AgentWorkspaceMember::getWorkspaceId).toList();
        if (adminWs.isEmpty()) {
            w.in(AgentTokenUsage::getWorkspaceId, memberWs).eq(AgentTokenUsage::getUserId, userId);
        } else {
            w.and(x -> x.in(AgentTokenUsage::getWorkspaceId, adminWs)
                .or(y -> y.in(AgentTokenUsage::getWorkspaceId, memberWs)
                    .eq(AgentTokenUsage::getUserId, userId)));
        }
        return w;
    }

    /**
     * 日汇总可见范围(同明细口径)
     */
    private LambdaQueryWrapper<AgentTokenUsageDaily> applyDailyVisibility(LambdaQueryWrapper<AgentTokenUsageDaily> w, AgentTokenUsageBo bo) {
        w.eq(bo.getAgentId() != null, AgentTokenUsageDaily::getAgentId, bo.getAgentId())
            .like(StringUtils.isNotBlank(bo.getModelName()), AgentTokenUsageDaily::getModelName, bo.getModelName())
            .eq(StringUtils.isNotBlank(bo.getSource()), AgentTokenUsageDaily::getSource, bo.getSource())
            .eq(StringUtils.isNotBlank(bo.getEstimated()), AgentTokenUsageDaily::getEstimated, bo.getEstimated());
        List<Long> deptWs = deptWorkspaceIds(bo.getDeptId());
        if (deptWs != null) {
            if (deptWs.isEmpty()) {
                w.eq(AgentTokenUsageDaily::getWorkspaceId, -1L);
            } else {
                w.in(AgentTokenUsageDaily::getWorkspaceId, deptWs);
            }
        }
        if (LoginHelper.isSuperAdmin()) {
            w.eq(bo.getWorkspaceId() != null, AgentTokenUsageDaily::getWorkspaceId, bo.getWorkspaceId())
                .eq(bo.getUserId() != null, AgentTokenUsageDaily::getUserId, bo.getUserId());
            return w;
        }
        Long userId = LoginHelper.getUserId();
        if (bo.getWorkspaceId() != null) {
            String role = memberRole(bo.getWorkspaceId(), userId);
            if (role == null) {
                throw new ServiceException("您不是该工作空间成员");
            }
            w.eq(AgentTokenUsageDaily::getWorkspaceId, bo.getWorkspaceId());
            if (!WorkspaceRole.ADMIN.getCode().equals(role)) {
                w.eq(AgentTokenUsageDaily::getUserId, userId);
            } else {
                w.eq(bo.getUserId() != null, AgentTokenUsageDaily::getUserId, bo.getUserId());
            }
            return w;
        }
        List<AgentWorkspaceMember> memberships = memberMapper.selectList(
            new LambdaQueryWrapper<AgentWorkspaceMember>().eq(AgentWorkspaceMember::getUserId, userId));
        if (memberships.isEmpty()) {
            w.eq(AgentTokenUsageDaily::getUserId, userId);
            return w;
        }
        List<Long> adminWs = memberships.stream()
            .filter(m -> WorkspaceRole.ADMIN.getCode().equals(m.getRole()))
            .map(AgentWorkspaceMember::getWorkspaceId).toList();
        List<Long> memberWs = memberships.stream().map(AgentWorkspaceMember::getWorkspaceId).toList();
        if (adminWs.isEmpty()) {
            w.in(AgentTokenUsageDaily::getWorkspaceId, memberWs).eq(AgentTokenUsageDaily::getUserId, userId);
        } else {
            w.and(x -> x.in(AgentTokenUsageDaily::getWorkspaceId, adminWs)
                .or(y -> y.in(AgentTokenUsageDaily::getWorkspaceId, memberWs)
                    .eq(AgentTokenUsageDaily::getUserId, userId)));
        }
        return w;
    }

    private String memberRole(Long workspaceId, Long userId) {
        AgentWorkspaceMember member = memberMapper.selectOne(new LambdaQueryWrapper<AgentWorkspaceMember>()
            .eq(AgentWorkspaceMember::getWorkspaceId, workspaceId)
            .eq(AgentWorkspaceMember::getUserId, userId)
            .last("limit 1"));
        return member == null ? null : member.getRole();
    }

    /**
     * 按归属部门解析空间ID集合; deptId 为空返回 null(不过滤)
     */
    private List<Long> deptWorkspaceIds(Long deptId) {
        if (deptId == null) {
            return null;
        }
        return workspaceMapper.selectList(new LambdaQueryWrapper<AgentWorkspace>()
                .eq(AgentWorkspace::getDeptId, deptId))
            .stream().map(AgentWorkspace::getId).toList();
    }

    private List<AgentUsageReportVo.RankItem> buildRank(Map<String, long[]> agg, long total) {
        return agg.entrySet().stream().map(e -> {
                AgentUsageReportVo.RankItem item = new AgentUsageReportVo.RankItem();
                item.setName(e.getKey());
                item.setTotalTokens(e.getValue()[0]);
                item.setCallCount(e.getValue()[1]);
                item.setRatio(total == 0 ? 0D : Math.round(e.getValue()[0] * 10000D / total) / 100D);
                return item;
            }).sorted(Comparator.comparing(AgentUsageReportVo.RankItem::getTotalTokens).reversed())
            .limit(10).toList();
    }

    /**
     * 批量填充 Agent 名称与用户昵称:一次 DB 查询替代原逐条 selectById
     */
    private void fillNames(List<AgentTokenUsageVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        // 1) 批量查 Agent 名称
        List<Long> agentIds = list.stream().map(AgentTokenUsageVo::getAgentId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> agentNameMap = agentIds.isEmpty() ? Map.of() : agentMapper.selectByIds(agentIds)
            .stream().collect(Collectors.toMap(AgentInfo::getId, a -> StringUtils.defaultIfBlank(a.getName(), ""), (a, b) -> a));
        // 2) 批量查用户昵称
        List<Long> userIds = list.stream().map(AgentTokenUsageVo::getUserId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nickNameMap = userIds.isEmpty() ? Map.of() : usageMapper.selectNickNamesByIds(userIds)
            .stream().collect(Collectors.toMap(
                row -> ((Number) row.get("userId")).longValue(),
                row -> row.get("nickName") == null ? "" : row.get("nickName").toString(),
                (a, b) -> a));
        // 3) 填充
        for (AgentTokenUsageVo v : list) {
            if (v.getAgentId() == null) {
                v.setAgentName("未知Agent");
            } else {
                String name = agentNameMap.get(v.getAgentId());
                v.setAgentName(StringUtils.isBlank(name) ? ("Agent#" + v.getAgentId()) : name);
            }
            if (v.getUserId() == null) {
                continue;
            }
            String nick = nickNameMap.get(v.getUserId());
            v.setUserName(StringUtils.isBlank(nick) ? String.valueOf(v.getUserId()) : nick);
        }
    }

    private LocalDate parseDate(String text, LocalDate dft) {
        if (StringUtils.isBlank(text)) {
            return dft;
        }
        String day = text.trim().substring(0, Math.min(10, text.trim().length()));
        try {
            return LocalDate.parse(day);
        } catch (Exception e) {
            return dft;
        }
    }

    private long nz(Integer v) {
        return v == null ? 0L : v.longValue();
    }
}
