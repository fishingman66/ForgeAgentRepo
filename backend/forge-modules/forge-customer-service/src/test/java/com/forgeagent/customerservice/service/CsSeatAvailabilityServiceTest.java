package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-1:坐席可用性(能不能转)单测。锁四件事:① 非营业时间直接拒绝且不查 DB;
 * ② 无在线坐席拒绝;③ 全员满载拒绝;④ 有未满载坐席放行。查询条件下推 workspace/online 必须验证。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsSeatAvailabilityServiceTest {

    @Mock private CsSeatMapper seatMapper;

    private CsSeatAvailabilityService service;

    @BeforeAll
    static void warmUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsSeat.class);
    }

    @BeforeEach
    void setUp() {
        service = new CsSeatAvailabilityService(seatMapper);
    }

    private static CsChannel channel(String hours) {
        CsChannel c = new CsChannel();
        c.setWorkspaceId(7L);
        c.setBusinessHoursJson(hours);
        return c;
    }

    private static CsSeat seat(String online, Integer load, Integer max) {
        CsSeat s = new CsSeat();
        s.setWorkspaceId(7L);
        s.setOnline(online);
        s.setCurrentLoad(load);
        s.setMaxLoad(max);
        return s;
    }

    @Test
    void noOnlineSeatRejectsWithConfigPresent() {
        // 营业时间配置存在但当天可能营业:此处只锁「无在线坐席」分支;
        // 营业时间本身的四象限由 CsBusinessHoursTest 用注入时刻独立覆盖。
        when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        var r = service.evaluate(channel(null));
        assertFalse(r.transferable());
        assertEquals("当前无在线坐席", r.reason());
    }

    @Test
    void noOnlineSeatRejectsAndPushesFiltersToSql() {
        when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        var r = service.evaluate(channel(null));
        assertFalse(r.transferable());
        assertEquals("当前无在线坐席", r.reason());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<LambdaQueryWrapper<CsSeat>> cap =
            org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(seatMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("workspace_id"), sql);
        assertTrue(sql.contains("online"), sql);
    }

    @Test
    void allSeatsFullRejects() {
        when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            seat("1", 5, 5), seat("1", 6, 5), seat("1", 3, 3)));
        var r = service.evaluate(channel(null));
        assertFalse(r.transferable());
        assertEquals("在线坐席均已满载", r.reason());
    }

    @Test
    void oneAvailableSeatPasses() {
        when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            seat("1", 5, 5), seat("1", 2, 5)));
        var r = service.evaluate(channel(null));
        assertTrue(r.transferable());
        assertEquals(null, r.reason());
    }

    @Test
    void nullLoadAndMaxUseDefaults() {
        // null load=0, null max=5 → 视为可用
        when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(seat("1", null, null)));
        assertTrue(service.evaluate(channel(null)).transferable());
    }

    @Test
    void dbFailureFailsClosed() {
        when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenThrow(new RuntimeException("DB down"));
        var r = service.evaluate(channel(null));
        assertFalse(r.transferable());
        assertEquals("坐席状态查询失败", r.reason());
    }

    @Test
    void nullChannelRejects() {
        assertFalse(service.evaluate(null).transferable());
    }
}
