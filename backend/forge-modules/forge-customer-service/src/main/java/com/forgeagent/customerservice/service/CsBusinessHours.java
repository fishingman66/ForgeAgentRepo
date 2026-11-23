package com.forgeagent.customerservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A-1:营业时间判定(纯函数,不依赖 Spring/DB)。
 *
 * <p>配置存 {@code agent_cs_channel.business_hours_json},形如:
 * <pre>{@code
 * {"enabled":true,"timezone":"Asia/Shanghai",
 *  "days":{"MON":[["09:00","18:00"]],"SAT":[["10:00","16:00"]]}}
 * }</pre>
 * 空/NULL/解析失败一律视为「24h 全天营业」——存量渠道零行为变更(fail-safe)。
 * 配置生效后,未配置的星期或空区间列表视为休息日;区间为左闭右开,支持跨零点。</p>
 */
public final class CsBusinessHours {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 默认时区:与国内私有化部署一致;配置可覆盖。 */
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private CsBusinessHours() {
    }

    /** 是否处于营业时间。configJson 空/坏 → true(全天),保证存量行为不变。 */
    public static boolean isOpen(String configJson) {
        return isOpen(configJson, Instant.now());
    }

    /** 可注入时刻的重载,便于单测跨日/跨时区边界。 */
    @SuppressWarnings("unchecked")
    public static boolean isOpen(String configJson, Instant now) {
        if (configJson == null || configJson.isBlank()) {
            return true;
        }
        try {
            Map<String, Object> cfg = MAPPER.readValue(configJson, Map.class);
            if (cfg == null || !Boolean.TRUE.equals(cfg.get("enabled"))) {
                return true;
            }
            String tz = cfg.get("timezone") == null ? DEFAULT_TIMEZONE : String.valueOf(cfg.get("timezone"));
            ZoneId zone;
            try {
                zone = ZoneId.of(tz);
            } catch (Exception e) {
                return true; // 非法时区按全天,不阻断转人工
            }
            Object daysRaw = cfg.get("days");
            if (!(daysRaw instanceof Map<?, ?> daysMap) || daysMap.isEmpty()) {
                return true; // 未配 days 视为全天,避免误伤存量
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> days = (Map<String, Object>) daysMap;
            DayOfWeek dow = now.atZone(zone).getDayOfWeek();
            String shortKey = dow.name().substring(0, 3).toUpperCase(Locale.ROOT);
            Object ranges = days.containsKey(shortKey) ? days.get(shortKey) : days.get(dow.name());
            if (!(ranges instanceof List<?> list)) {
                return false; // 配置已生效但当天未排班 → 休息
            }
            List<String[]> parsed = parseRanges(list);
            if (parsed.isEmpty()) {
                return false; // 区间全非法 → 当天休息,不把访客转入无人队列
            }
            LocalTime t = now.atZone(zone).toLocalTime();
            for (String[] r : parsed) {
                LocalTime from = LocalTime.parse(r[0]);
                LocalTime to = LocalTime.parse(r[1]);
                if (from.isAfter(to)) {
                    // 跨零点:[22:00, 02:00] → t>=22:00 或 t<02:00
                    if (!t.isBefore(from) || t.isBefore(to)) {
                        return true;
                    }
                } else if (!t.isBefore(from) && t.isBefore(to)) {
                    // 同日左闭右开:[09:00, 18:00)
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true; // 任何配置异常按全天,不阻断转人工
        }
    }

    private static List<String[]> parseRanges(List<?> list) {
        List<String[]> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof List<?> pair && pair.size() >= 2) {
                String a = String.valueOf(pair.get(0));
                String b = String.valueOf(pair.get(1));
                try {
                    LocalTime.parse(a);
                    LocalTime.parse(b);
                    out.add(new String[]{a, b});
                } catch (Exception ignored) {
                    // 非法区间跳过,不因单条坏配置炸掉整个判定
                }
            }
        }
        return out;
    }
}
