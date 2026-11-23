package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-1:营业时间判定单测。锁四件事:① 空/坏配置=24h 全天(存量零变更);
 * ② 星期与区间边界(左闭右开);③ 跨零点区间;④ 时区换算(UTC 时刻→Asia/Shanghai 本地时间)。
 */
@Tag("dev")
class CsBusinessHoursTest {

    /** 2026-11-23 是周一;UTC 01:00 = 上海 09:00。 */
    private static final Instant MON_0900_UTC = local("2026-11-23T09:00:00", "Asia/Shanghai");
    /** 上海 18:00 整,区间 [09:00,18:00) 应判休息。 */
    private static final Instant MON_1800_UTC = local("2026-11-23T18:00:00", "Asia/Shanghai");
    /** 上海 17:59。 */
    private static final Instant MON_1759_UTC = local("2026-11-23T17:59:00", "Asia/Shanghai");
    /** 2026-11-28 是周六。 */
    private static final Instant SAT_1100_UTC = local("2026-11-28T11:00:00", "Asia/Shanghai");

    private static Instant local(String isoLocal, String zone) {
        return java.time.LocalDateTime.parse(isoLocal).atZone(ZoneId.of(zone)).toInstant();
    }

    private static String weekdayConfig() {
        return """
            {"enabled":true,"timezone":"Asia/Shanghai",
             "days":{"MON":[["09:00","18:00"]],"SAT":[["10:00","16:00"]]}}
            """;
    }

    @Test
    void blankOrInvalidConfigMeansAlwaysOpen() {
        assertTrue(CsBusinessHours.isOpen(null, MON_1800_UTC));
        assertTrue(CsBusinessHours.isOpen("", MON_1800_UTC));
        assertTrue(CsBusinessHours.isOpen("{bad json", MON_1800_UTC));
        assertTrue(CsBusinessHours.isOpen("{\"enabled\":false}", MON_1800_UTC));
        assertTrue(CsBusinessHours.isOpen("{\"enabled\":true,\"timezone\":\"Bad/Zone\"}", MON_1800_UTC));
    }

    @Test
    void configuredWeekdayRangeIsLeftClosedRightOpen() {
        String cfg = weekdayConfig();
        assertTrue(CsBusinessHours.isOpen(cfg, MON_0900_UTC), "09:00 整应营业(左闭)");
        assertTrue(CsBusinessHours.isOpen(cfg, MON_1759_UTC), "17:59 应营业");
        assertFalse(CsBusinessHours.isOpen(cfg, MON_1800_UTC), "18:00 整应休息(右开)");
    }

    @Test
    void unconfiguredDayIsClosed() {
        // 周二未配置 → 休息;周六配置 10:00~16:00,11:00 营业
        Instant tue = local("2026-11-24T11:00:00", "Asia/Shanghai");
        assertFalse(CsBusinessHours.isOpen(weekdayConfig(), tue));
        assertTrue(CsBusinessHours.isOpen(weekdayConfig(), SAT_1100_UTC));
    }

    @Test
    void crossMidnightRangeSpansTwoDays() {
        String cfg = """
            {"enabled":true,"timezone":"Asia/Shanghai","days":{"MON":[["22:00","02:00"]]}}
            """;
        assertTrue(CsBusinessHours.isOpen(cfg, local("2026-11-23T22:30:00", "Asia/Shanghai")));
        assertTrue(CsBusinessHours.isOpen(cfg, local("2026-11-23T01:30:00", "Asia/Shanghai")),
            "跨零点区间的前半段(01:30)按当天 MON 配置命中");
        assertFalse(CsBusinessHours.isOpen(cfg, local("2026-11-23T12:00:00", "Asia/Shanghai")));
    }

    @Test
    void invalidRangesMeanClosedNotOpen() {
        String cfg = """
            {"enabled":true,"timezone":"Asia/Shanghai","days":{"MON":[["bad","range"]]}}
            """;
        assertFalse(CsBusinessHours.isOpen(cfg, MON_1200()), "坏区间应 fail-closed 到不转人工");
    }

    private static Instant MON_1200() {
        return local("2026-11-23T12:00:00", "Asia/Shanghai");
    }
}
