package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsEnterprise;
import com.forgeagent.callcenter.mapper.CsEnterpriseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业域配额与到期门禁(P0-1)。
 *
 * <p><b>零回退契约</b>:{@code enterprise-enabled=false}(默认)或 workspace 未关联企业行时,
 * 所有 check 直接 ALLOW,不查库、不改变任何既有行为;只有显式开启且企业存在才生效。</p>
 *
 * <p><b>继承口径</b>:子企业配额字段为 null 时,沿 id_path 由近及远找第一个非 null 的祖先;
 * 祖先全空则回退 CallcenterProperties 默认值(maxSeats / maxConcurrentCalls / budgetMonthlyMinutes)。</p>
 *
 * <p>数据隔离仍以 workspace 为主键:本服务只按 workspace_id / 企业 id 精确查询,
 * 不做跨空间聚合(与 docs/07「不做多租户」决策兼容,企业只是运营上层)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnterpriseQuotaService {

    /** 门禁判定结果。 */
    public record Decision(boolean allowed, String reason, long limit, long current) {
        public static Decision allow(long limit, long current) {
            return new Decision(true, null, limit, current);
        }

        public static Decision deny(String reason, long limit, long current) {
            return new Decision(false, reason, limit, current);
        }
    }

    /** 解析后的有效配额(已含继承与默认值)。 */
    public record EffectiveQuota(Long enterpriseId, String code, boolean expired, boolean disabled,
                                 long seatLimit, long groupLimit, long channelLimit,
                                 long concurrentLimit, long budgetMinutes) {
    }

    private final CsEnterpriseMapper mapper;
    private final CallcenterProperties properties;

    /** 是否启用企业域门禁(默认关闭)。 */
    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getEnterpriseEnabled());
    }

    /** 按 workspace 精确查询企业行(跨空间零可见:查不到即返回 null)。 */
    public CsEnterprise findByWorkspace(Long workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<CsEnterprise>()
            .eq(CsEnterprise::getWorkspaceId, workspaceId).last("limit 1"));
    }

    /**
     * 解析 workspace 的有效配额。未启用/无企业行时返回基于默认值的配额(不拦截)。
     */
    public EffectiveQuota effective(Long workspaceId) {
        Defaults d = defaults();
        CsEnterprise self = enabled() ? findByWorkspace(workspaceId) : null;
        if (self == null) {
            return new EffectiveQuota(null, null, false, false,
                d.seats, d.groups, d.channels, d.concurrent, d.budget);
        }
        Map<Long, CsEnterprise> ancestors = loadAncestors(self);
        long seats = inherit(self, ancestors, CsEnterprise::getSeatLimit, d.seats);
        long groups = inherit(self, ancestors, CsEnterprise::getGroupLimit, d.groups);
        long channels = inherit(self, ancestors, CsEnterprise::getChannelLimit, d.channels);
        long concurrent = inherit(self, ancestors, CsEnterprise::getConcurrentLimit, d.concurrent);
        long budget = inherit(self, ancestors, CsEnterprise::getBudgetMinutes, d.budget);
        boolean expired = self.getExpireTime() != null && self.getExpireTime().before(new Date());
        boolean disabled = "1".equals(self.getStatus());
        return new EffectiveQuota(self.getId(), self.getCode(), expired, disabled,
            seats, groups, channels, concurrent, budget);
    }

    /** 坐席数门禁:当前数 >= 上限时拒绝。 */
    public Decision checkSeat(Long workspaceId, long current) {
        return check(workspaceId, current, QuotaKind.SEAT);
    }

    /** 语音渠道数门禁。 */
    public Decision checkChannel(Long workspaceId, long current) {
        return check(workspaceId, current, QuotaKind.CHANNEL);
    }

    /** 并发通话数门禁。 */
    public Decision checkConcurrent(Long workspaceId, long current) {
        return check(workspaceId, current, QuotaKind.CONCURRENT);
    }

    /** 到期/停用门禁:过期或停用企业拒绝写入(历史数据仍可查,不在此入口拦截读)。 */
    public Decision checkExpiry(Long workspaceId) {
        if (!enabled()) {
            return Decision.allow(-1, -1);
        }
        EffectiveQuota q = effective(workspaceId);
        if (q.enterpriseId() == null) {
            return Decision.allow(-1, -1);
        }
        if (q.disabled()) {
            return Decision.deny("企业已停用", -1, -1);
        }
        if (q.expired()) {
            return Decision.deny("企业已到期", -1, -1);
        }
        return Decision.allow(-1, -1);
    }

    private enum QuotaKind { SEAT, CHANNEL, CONCURRENT }

    private Decision check(Long workspaceId, long current, QuotaKind kind) {
        if (!enabled() || current < 0) {
            return Decision.allow(-1, current);
        }
        EffectiveQuota q = effective(workspaceId);
        if (q.enterpriseId() == null) {
            return Decision.allow(-1, current);
        }
        long limit = switch (kind) {
            case SEAT -> q.seatLimit();
            case CHANNEL -> q.channelLimit();
            case CONCURRENT -> q.concurrentLimit();
        };
        if (limit <= 0) {
            return Decision.allow(limit, current);
        }
        if (current >= limit) {
            return Decision.deny(switch (kind) {
                case SEAT -> "坐席数已达企业上限 " + limit;
                case CHANNEL -> "语音渠道数已达企业上限 " + limit;
                case CONCURRENT -> "并发通话数已达企业上限 " + limit;
            }, limit, current);
        }
        return Decision.allow(limit, current);
    }

    private Defaults defaults() {
        int seats = properties.getMaxSeats() == null ? 30 : properties.getMaxSeats();
        int concurrent = properties.getMaxConcurrentCalls() == null ? 30 : properties.getMaxConcurrentCalls();
        int budget = properties.getBudgetMonthlyMinutes() == null ? 400000 : properties.getBudgetMonthlyMinutes();
        // 渠道无独立配置口径,默认按坐席上限( <= seats ),避免引入新配置项。
        return new Defaults(seats, seats, seats, concurrent, budget);
    }

    private record Defaults(long seats, long groups, long channels, long concurrent, long budget) {
    }

    /** 解析 id_path 中的祖先企业(由近及远),一次 IN 查询,避免 N+1。 */
    private Map<Long, CsEnterprise> loadAncestors(CsEnterprise self) {
        Map<Long, CsEnterprise> result = new LinkedHashMap<>();
        String path = self.getIdPath();
        if (path == null || path.isBlank()) {
            return result;
        }
        List<Long> ids = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isBlank()) {
                continue;
            }
            try {
                long id = Long.parseLong(seg.trim());
                if (id != 0 && id != Long.valueOf(self.getId() == null ? -1 : self.getId())) {
                    ids.add(0, id); // 由近及远
                }
            } catch (NumberFormatException ignored) {
                // 脏路径段跳过,不阻断门禁
            }
        }
        if (ids.isEmpty()) {
            return result;
        }
        List<CsEnterprise> rows = mapper.selectList(new LambdaQueryWrapper<CsEnterprise>()
            .in(CsEnterprise::getId, ids));
        if (rows != null) {
            for (CsEnterprise row : rows) {
                result.put(row.getId(), row);
            }
        }
        // IN 查询不保证顺序:按 id_path 由近及远重排,继承才取到最近祖先
        List<CsEnterprise> ordered = new ArrayList<>();
        for (Long id : ids) {
            CsEnterprise row = result.get(id);
            if (row != null) {
                ordered.add(row);
            }
        }
        return new LinkedHashMap<>(ordered.stream().collect(java.util.stream.Collectors.toMap(
            CsEnterprise::getId, java.util.function.Function.identity(), (a, b) -> a, LinkedHashMap::new)));
    }

    /** 自身为 null 时沿祖先链由近及远找第一个非 null;全空回退默认值。 */
    private long inherit(CsEnterprise self, Map<Long, CsEnterprise> ancestors,
                         java.util.function.Function<CsEnterprise, Integer> getter, long fallback) {
        Integer own = getter.apply(self);
        if (own != null) {
            return own;
        }
        for (CsEnterprise ancestor : ancestors.values()) {
            Integer v = getter.apply(ancestor);
            if (v != null) {
                return v;
            }
        }
        return fallback;
    }
}
