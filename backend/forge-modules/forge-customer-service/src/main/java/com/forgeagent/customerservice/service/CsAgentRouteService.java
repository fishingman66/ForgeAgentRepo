package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsAgentRoute;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.mapper.CsAgentRouteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 多 Agent 意图路由(P3):把「意图」映射到「专家 Agent」。
 *
 * <p>典型配置:退款/退货走售后 Agent、物流/发货走订单 Agent、发票走财务 Agent。
 * 没配任何路由时行为完全不变(回落渠道默认 agentId)。</p>
 *
 * <p><b>为什么只在会话首条消息生效</b>:客服会话与 {@code agent_session} 一对一绑定,
 * 而 {@code ChatSendBo.agentId} 仅在 sessionId 为空时用于建会话。每轮换 Agent 等于
 * 每轮新建会话,对话上下文全断——所以首个带明确意图的消息决定这通会话由谁接待。</p>
 *
 * <p>匹配优先级:渠道级配置 > 空间级(channel_code 为空);同级取 priority 大者。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsAgentRouteService {

    private static final long SNAPSHOT_TTL_MS = 60_000L;

    private final CsAgentRouteMapper routeMapper;
    private final CsIntentService intentService;
    private final CsModuleProperties properties;
    /** P3:路由目标必须存在且可用,配错不该把线上对话打挂。 */
    private final com.forgeagent.agentcore.mapper.AgentInfoMapper agentInfoMapper;

    private volatile Snapshot snapshot;

    private record Snapshot(Long workspaceId, long expireAt, List<CsAgentRoute> routes) {
    }

    /** 按消息内容判定该用哪个 Agent;返回 null 表示未配置路由,调用方用渠道默认值。 */
    public Long resolveAgentId(CsChannel channel, String content) {
        if (!Boolean.TRUE.equals(properties.getAgentRoutingEnabled())
            || channel == null || content == null || content.isBlank()) {
            log.debug("[CS-Route] 跳过:开关={} channel={} content空={}",
                properties.getAgentRoutingEnabled(), channel == null ? null : channel.getChannelCode(),
                content == null || content.isBlank());
            return null;
        }
        List<CsAgentRoute> routes = routes(channel.getWorkspaceId());
        if (routes.isEmpty()) {
            log.debug("[CS-Route] 跳过:ws={} 无启用路由", channel.getWorkspaceId());
            return null;
        }
        CsIntentService.Match intent = intentService.classify(channel.getWorkspaceId(), content, List.of());
        if (intent == null) {
            log.debug("[CS-Route] 跳过:意图未命中 content={}", content);
            return null;
        }
        Long picked = pick(routes, channel.getChannelCode(), intent.code());
        Long valid = validAgent(picked, channel.getWorkspaceId());
        log.debug("[CS-Route] ws={} channel={} intent={} routes={} picked={} valid={}",
            channel.getWorkspaceId(), channel.getChannelCode(), intent.code(), routes.size(), picked, valid);
        return valid;
    }

    /**
     * 校验路由目标可用:不存在、已停用或跨空间的 Agent 都回落渠道默认。
     *
     * <p>运营把 agentId 填错时,宁可退回默认 Agent 继续服务,也不要让整条对话 500。</p>
     */
    private Long validAgent(Long agentId, Long workspaceId) {
        if (agentId == null) {
            return null;
        }
        com.forgeagent.agentcore.domain.AgentInfo info = agentInfoMapper.selectById(agentId);
        if (info == null || !"1".equals(info.getStatus())) {
            log.warn("[CS] 路由目标 Agent 不存在或已停用，回落默认 agentId={}", agentId);
            return null;
        }
        if (info.getWorkspaceId() != null && !info.getWorkspaceId().equals(workspaceId)) {
            log.warn("[CS] 路由目标 Agent 属于其他空间，回落默认 agentId={} targetWorkspace={}",
                agentId, info.getWorkspaceId());
            return null;
        }
        return agentId;
    }

    /**
     * 路由匹配(纯函数,单测锁定):渠道级优先,同级按 priority 降序。
     */
    public static Long pick(List<CsAgentRoute> routes, String channelCode, String intentCode) {
        if (routes == null || routes.isEmpty() || intentCode == null) {
            return null;
        }
        CsAgentRoute best = null;
        boolean bestChannelScoped = false;
        for (CsAgentRoute route : routes) {
            if (route == null || !"1".equals(route.getEnabled()) || route.getAgentId() == null) {
                continue;
            }
            if (!intentCode.equals(route.getIntentCode())) {
                continue;
            }
            boolean channelScoped = route.getChannelCode() != null && !route.getChannelCode().isBlank();
            if (channelScoped && !Objects.equals(route.getChannelCode(), channelCode)) {
                continue;
            }
            if (best == null) {
                best = route;
                bestChannelScoped = channelScoped;
                continue;
            }
            // 渠道级压过空间级;同级别比 priority
            if (channelScoped && !bestChannelScoped) {
                best = route;
                bestChannelScoped = true;
                continue;
            }
            if (channelScoped == bestChannelScoped && priorityOf(route) > priorityOf(best)) {
                best = route;
            }
        }
        return best == null ? null : best.getAgentId();
    }

    private static int priorityOf(CsAgentRoute route) {
        return route.getPriority() == null ? 0 : route.getPriority();
    }

    public List<CsAgentRoute> list(Long workspaceId, String channelCode) {
        LambdaQueryWrapper<CsAgentRoute> query = new LambdaQueryWrapper<CsAgentRoute>()
            .eq(CsAgentRoute::getWorkspaceId, workspaceId);
        if (channelCode != null && !channelCode.isBlank()) {
            query.and(w -> w.isNull(CsAgentRoute::getChannelCode)
                .or().eq(CsAgentRoute::getChannelCode, channelCode));
        }
        return routeMapper.selectList(query.orderByDesc(CsAgentRoute::getPriority));
    }

    /** 新增/编辑:同空间同渠道同意图唯一,存在则更新。 */
    public CsAgentRoute save(CsAgentRoute route, Long workspaceId) {
        if (route == null || route.getIntentCode() == null || route.getIntentCode().isBlank()) {
            throw new ServiceException("意图编码不能为空");
        }
        if (route.getAgentId() == null) {
            throw new ServiceException("目标 Agent 不能为空");
        }
        route.setWorkspaceId(workspaceId);
        route.setIntentCode(route.getIntentCode().trim().toUpperCase());
        route.setChannelCode(route.getChannelCode() == null || route.getChannelCode().isBlank()
            ? null : route.getChannelCode().trim());
        route.setEnabled(route.getEnabled() == null ? "1" : route.getEnabled());
        route.setPriority(route.getPriority() == null ? 0 : route.getPriority());
        CsAgentRoute existing = routeMapper.selectOne(new LambdaQueryWrapper<CsAgentRoute>()
            .eq(CsAgentRoute::getWorkspaceId, workspaceId)
            .eq(CsAgentRoute::getIntentCode, route.getIntentCode())
            .eq(route.getChannelCode() == null, CsAgentRoute::getChannelCode, route.getChannelCode())
            .last("limit 1"));
        if (existing != null) {
            existing.setAgentId(route.getAgentId());
            existing.setPriority(route.getPriority());
            existing.setEnabled(route.getEnabled());
            existing.setUpdateTime(new Date());
            routeMapper.updateById(existing);
            invalidate();
            return existing;
        }
        route.setCreateTime(new Date());
        route.setUpdateTime(new Date());
        routeMapper.insert(route);
        invalidate();
        return route;
    }

    public boolean remove(Long id, Long workspaceId) {
        CsAgentRoute existing = id == null ? null : routeMapper.selectById(id);
        if (existing == null) {
            return true;
        }
        if (!existing.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("路由不存在或无权操作");
        }
        boolean removed = routeMapper.deleteById(id) > 0;
        invalidate();
        return removed;
    }

    /** 变更后立即失效快照,运营改完下一轮就生效。 */
    public void invalidate() {
        snapshot = null;
    }

    /** 启用中的路由(60s 快照;空表也缓存,避免每条消息都查库)。 */
    private List<CsAgentRoute> routes(Long workspaceId) {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && current.expireAt() > now
            && Objects.equals(current.workspaceId(), workspaceId)) {
            return current.routes();
        }
        List<CsAgentRoute> loaded = routeMapper.selectList(new LambdaQueryWrapper<CsAgentRoute>()
            .eq(CsAgentRoute::getWorkspaceId, workspaceId)
            .eq(CsAgentRoute::getEnabled, "1"));
        snapshot = new Snapshot(workspaceId, now + SNAPSHOT_TTL_MS, loaded);
        return loaded;
    }
}
