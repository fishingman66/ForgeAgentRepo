package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsBlackPhone;
import com.forgeagent.callcenter.mapper.CsBlackPhoneMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 号码黑名单判定(P2-1):前缀 + 方向 + 时段。默认关闭,零查库零拦截。
 *
 * <p>命中只返回 Decision 供入口拒绝,不发回调 HTTP(callbackUrl 仅留存)——
 * FR 红线与"无出向话单出口"事实一致,不伪造外部推送能力。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlackPhoneService {

    /** 判定结果。 */
    public record Decision(boolean blocked, String prefix, String reason) {
        public static Decision pass() {
            return new Decision(false, null, null);
        }
    }

    private final CsBlackPhoneMapper mapper;
    private final CallcenterProperties properties;

    /** 是否启用(默认关闭)。 */
    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getBlacklistEnabled());
    }

    /** 判定号码是否命中黑名单(默认关闭/号码为空一律放行)。 */
    public Decision check(Long workspaceId, String number, int direction) {
        if (!enabled() || workspaceId == null || number == null || number.isBlank()) {
            return Decision.pass();
        }
        List<CsBlackPhone> rows = mapper.selectList(new LambdaQueryWrapper<CsBlackPhone>()
            .eq(CsBlackPhone::getWorkspaceId, workspaceId)
            .eq(CsBlackPhone::getStatus, "0"));
        if (rows == null || rows.isEmpty()) {
            return Decision.pass();
        }
        // 最长前缀优先(对齐参考"最长字冠匹配"语义)
        List<CsBlackPhone> sorted = rows.stream()
            .sorted(Comparator.comparingInt((CsBlackPhone b) ->
                b.getNumPrefix() == null ? 0 : b.getNumPrefix().length()).reversed())
            .toList();
        for (CsBlackPhone b : sorted) {
            if (matches(b, number, direction)) {
                return new Decision(true, b.getNumPrefix(), b.getReason());
            }
        }
        return Decision.pass();
    }

    /** 单条规则匹配(纯函数):前缀 + 方向 + 时段,全满足才命中。 */
    public static boolean matches(CsBlackPhone rule, String number, int direction) {
        if (rule == null || rule.getNumPrefix() == null || rule.getNumPrefix().isBlank()) {
            return false;
        }
        if (number == null || !number.startsWith(rule.getNumPrefix())) {
            return false;
        }
        Integer d = rule.getCallDirection();
        int dir = d == null ? 2 : d;
        if (dir != 2 && dir != direction) {
            return false;
        }
        return inSchedule(rule.getSchedule(), LocalTime.now());
    }

    /** 时段判定 HH:mm-HH:mm;空/非法=全天。跨零点(如 22:00-06:00)也支持。 */
    public static boolean inSchedule(String schedule, LocalTime now) {
        if (schedule == null || schedule.isBlank() || !schedule.contains("-")) {
            return true;
        }
        String[] parts = schedule.split("-", 2);
        try {
            LocalTime from = LocalTime.parse(parts[0].trim());
            LocalTime to = LocalTime.parse(parts[1].trim());
            if (from.equals(to)) {
                return true;
            }
            boolean afterFrom = !now.isBefore(from);
            boolean beforeTo = now.isBefore(to);
            return from.isBefore(to)
                ? afterFrom && beforeTo
                : afterFrom || beforeTo; // 跨零点
        } catch (Exception e) {
            return true; // 非法时段不误伤,按全天
        }
    }

    /** 供日志/留痕用:命中时间快照(不对外发请求)。 */
    public static Date now() {
        return new Date();
    }
}
