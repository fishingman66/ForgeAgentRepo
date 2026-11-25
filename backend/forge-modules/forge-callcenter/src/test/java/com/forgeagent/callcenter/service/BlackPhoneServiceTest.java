package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsBlackPhone;
import com.forgeagent.callcenter.mapper.CsBlackPhoneMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-1 号码黑名单契约测试:前缀命中、方向、时段、最长优先、默认零回退。
 */
@Tag("dev")
class BlackPhoneServiceTest {

    private CsBlackPhoneMapper mapper;
    private CallcenterProperties properties;
    private BlackPhoneService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsBlackPhone.class);
        mapper = mock(CsBlackPhoneMapper.class);
        properties = new CallcenterProperties();
        service = new BlackPhoneService(mapper, properties);
    }

    private CsBlackPhone rule(String prefix, Integer direction, String schedule) {
        CsBlackPhone b = new CsBlackPhone();
        b.setNumPrefix(prefix);
        b.setCallDirection(direction);
        b.setSchedule(schedule);
        b.setStatus("0");
        b.setReason("test");
        return b;
    }

    @Test
    void disabledByDefaultNoQuery() {
        assertFalse(service.enabled());
        assertFalse(service.check(1L, "13800001111", 0).blocked());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void prefixAndDirectionMatching() {
        CsBlackPhone b = rule("138", 0, null); // 仅呼入
        assertTrue(BlackPhoneService.matches(b, "13800001111", 0), "呼入命中");
        assertFalse(BlackPhoneService.matches(b, "13900001111", 0), "前缀不符");
        assertFalse(BlackPhoneService.matches(b, "13800001111", 1), "呼出不命中");
        assertTrue(BlackPhoneService.matches(rule("138", 2, null), "13800001111", 1), "双向命中");
    }

    @Test
    void longestPrefixWins() {
        properties.setBlacklistEnabled(true);
        when(mapper.selectList(any())).thenReturn(List.of(
            rule("138", 2, null), rule("1380000", 2, null)));
        var d = service.check(1L, "13800001111", 0);
        assertTrue(d.blocked());
        assertEquals("1380000", d.prefix(), "最长前缀优先");
    }

    @Test
    void scheduleHandlesDayAndCrossMidnight() {
        LocalTime day = LocalTime.of(10, 0);
        LocalTime night = LocalTime.of(23, 0);
        assertTrue(BlackPhoneService.inSchedule("09:00-18:00", day));
        assertFalse(BlackPhoneService.inSchedule("09:00-18:00", night));
        assertTrue(BlackPhoneService.inSchedule("22:00-06:00", night), "跨零点命中");
        assertFalse(BlackPhoneService.inSchedule("22:00-06:00", day), "跨零点外不命中");
        assertTrue(BlackPhoneService.inSchedule(null, day), "空时段=全天");
        assertTrue(BlackPhoneService.inSchedule("bad-slot", day), "非法时段不误伤");
    }

    @Test
    void enabledNoRulePasses() {
        properties.setBlacklistEnabled(true);
        when(mapper.selectList(any())).thenReturn(List.of());
        assertFalse(service.check(1L, "13800001111", 0).blocked());
        assertFalse(service.check(1L, null, 0).blocked(), "无号码放行");
    }
}
