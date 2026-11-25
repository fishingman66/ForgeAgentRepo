package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsDidRoute;
import com.forgeagent.callcenter.mapper.CsDidRouteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * DID 字冠路由(P2-2):对齐参考 CacheService.getRouteGetway + FsListen.makeCall 的真实语义。
 *
 * <p><b>匹配</b>：{@code called.contains(routeNum)}（任意位置子串，不是前缀）；
 * `*` 整串相等作 catch-all；按 routeNum 长度降序取<b>第一条命中</b>（复刻 SQL
 * `order by length(route_num) desc` + LinkedHashMap 首命中 break）。
 * <b>变换</b>：callerPrefix / calledPrefix 前缀拼接，作用于<b>原始号码</b>——
 * 调用方必须在 CsPiiMasker.mask 之前调用本服务，脱敏后号码无法匹配字冠。</p>
 *
 * <p>默认关闭（agent.callcenter.did-route-enabled=false），关闭/无配置时原样返回，零回退。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DidRouteResolver {

    /** 路由决策：命中的规则 + 变换后的号码（null 表示未命中/未变换）。 */
    public record Decision(CsDidRoute rule, String caller, String callee, String target) {
        public static Decision none(String caller, String callee) {
            return new Decision(null, caller, callee, null);
        }

        public boolean matched() {
            return rule != null;
        }
    }

    private final CsDidRouteMapper mapper;
    private final CallcenterProperties properties;

    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getDidRouteEnabled());
    }

    /** 按 workspace 取启用规则，按字冠长度降序（复刻参考 SQL 排序，不依赖 DB 返回顺序）。 */
    public List<CsDidRoute> rules(Long workspaceId) {
        if (!enabled() || workspaceId == null) {
            return List.of();
        }
        List<CsDidRoute> rows = mapper.selectList(new LambdaQueryWrapper<CsDidRoute>()
            .eq(CsDidRoute::getWorkspaceId, workspaceId)
            .eq(CsDidRoute::getStatus, "0"));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
            .sorted(Comparator.comparingInt((CsDidRoute r) ->
                r.getRouteNum() == null ? 0 : r.getRouteNum().length()).reversed())
            .toList();
    }

    /** 完整判定：原始被叫匹配 → 号码变换 → 目标路由码。未命中返回原号码。 */
    public Decision resolve(Long workspaceId, String caller, String callee) {
        List<CsDidRoute> sorted = rules(workspaceId);
        CsDidRoute hit = match(sorted, callee);
        if (hit == null) {
            return Decision.none(caller, callee);
        }
        return new Decision(hit, transform(hit.getCallerPrefix(), caller),
            transform(hit.getCalledPrefix(), callee), hit.getTarget());
    }

    /** 纯匹配：任意位置包含 或 '*' 兜底；首命中（列表须已按长度降序）。 */
    public static CsDidRoute match(List<CsDidRoute> sortedRules, String called) {
        if (sortedRules == null || sortedRules.isEmpty() || called == null || called.isBlank()) {
            return null;
        }
        for (CsDidRoute r : sortedRules) {
            if (r == null || r.getRouteNum() == null) {
                continue;
            }
            String route = r.getRouteNum();
            if ("*".equals(route) || called.contains(route)) {
                return r;
            }
        }
        return null;
    }

    /** 前缀拼接（对齐 FsListen.makeCall:452-456）；prefix 空/号空时原样返回。 */
    public static String transform(String prefix, String number) {
        if (prefix == null || prefix.isBlank() || number == null || number.isBlank()) {
            return number;
        }
        return prefix + number;
    }
}
