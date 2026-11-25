package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsAgentStateLog;
import com.forgeagent.callcenter.domain.SeatStates;
import com.forgeagent.callcenter.mapper.CsAgentStateLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坐席状态扩展与轨迹契约测试(P1-2):状态兼容、乱序不回退、跨月归档键。
 */
@Tag("dev")
class AgentStateLogServiceTest {

    private CsAgentStateLogMapper mapper;
    private CallcenterProperties properties;
    private AgentStateLogService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsAgentStateLog.class);
        mapper = mock(CsAgentStateLogMapper.class);
        properties = new CallcenterProperties();
        service = new AgentStateLogService(mapper, properties);
    }

    // ---------------- 状态扩展向前兼容 ----------------

    @Test
    void statesExtendedButLegacyPreserved() {
        // 旧 5 态原样保留
        for (String s : new String[]{"OFFLINE", "READY", "PAUSE", "TALKING", "WRAP_UP"}) {
            assertTrue(SeatStates.known(s), s + " 仍可用");
        }
        // 新增 7 态
        for (String s : new String[]{"CONSULT", "HOLD", "AFTER", "MONITOR", "INSERT", "LISTEN", "WHISPER"}) {
            assertTrue(SeatStates.known(s), s + " 已扩展");
        }
        assertEquals("READY", SeatStates.normalize(" ready "), "规范化大小写与空白");
        assertEquals("CUSTOM_VENDOR_STATE", SeatStates.normalize("custom_vendor_state"),
            "未知厂商态原样大写保留,不丢数据");
    }

    // ---------------- 乱序不回退 ----------------

    @Test
    void outOfOrderStateNeverRegresses() {
        // rank 单调:OFFLINE(1) → TALKING(4) 可进;TALKING(4) → READY(2) 回退拒绝
        assertTrue(SeatStates.advances("OFFLINE", "READY"));
        assertTrue(SeatStates.advances("TALKING", "WRAP_UP"));
        assertFalse(SeatStates.advances("TALKING", "READY"), "坐席通话中不该被 READY 覆盖");
        assertFalse(SeatStates.advances("WHISPER", "READY"), "高 rank 不被低 rank 回退");
        assertFalse(SeatStates.advances("READY", "VENDOR_UNKNOWN"), "未知态不覆盖已知态");
    }

    @Test
    void defaultDisabledNoWrite() {
        assertFalse(service.enabled(), "默认关闭");
        assertFalse(service.record(1L, "1001", null, "OFFLINE", "READY", 1000L, 2000L));
        verify(mapper, never()).insert(any(CsAgentStateLog.class));
    }

    @Test
    void outOfOrderRecordSkipped() {
        properties.setAgentStateLogEnabled(true);
        // rank 回退
        assertFalse(service.record(1L, "1001", null, "TALKING", "READY", 1000L, 2000L));
        // 时间倒退
        assertFalse(service.record(1L, "1001", null, "OFFLINE", "READY", 5000L, 3000L));
        verify(mapper, never()).insert(any(CsAgentStateLog.class));
    }

    // ---------------- 时长 + 跨月归档键 ----------------

    @Test
    void recordWritesDurationAndMonthKey() {
        properties.setAgentStateLogEnabled(true);
        long t1 = Instant.parse("2026-11-30T23:59:30Z").toEpochMilli();
        long t2 = Instant.parse("2026-12-01T00:00:10Z").toEpochMilli();

        assertTrue(service.record(1L, "1001", 7L, "OFFLINE", "READY", t1, t2));
        ArgumentCaptor<CsAgentStateLog> cap = ArgumentCaptor.forClass(CsAgentStateLog.class);
        verify(mapper).insert(cap.capture());
        assertEquals(40, cap.getValue().getDurationSec(), "前态持续 40 秒");
        assertEquals("OFFLINE", cap.getValue().getBeforeState());
        assertEquals("READY", cap.getValue().getState());
        assertEquals("1001", cap.getValue().getWorkNo());
    }

    @Test
    void monthKeyDeterministicAcrossMonths() {
        long nov = LocalDate.of(2026, 11, 15).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli();
        long dec = LocalDate.of(2026, 12, 15).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli();
        assertEquals("2026-11", AgentStateLogService.monthKey(nov));
        assertEquals("2026-12", AgentStateLogService.monthKey(dec));
        // 同月多次调用稳定(纯函数)
        assertEquals(AgentStateLogService.monthKey(nov), AgentStateLogService.monthKey(nov));
    }

    @Test
    void latestQueryGuardedWhenDisabled() {
        assertEquals(null, service.latest("1001"), "关闭时不查库");
        verify(mapper, never()).selectOne(any());
    }
}
