package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.mapper.CsKgExpandMetricMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索扩展指标回归(P3 / M4 后续):标志位映射、空空间跳过、失败吞掉。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsKgExpandMetricServiceTest {

    @Mock
    private CsKgExpandMetricMapper metricMapper;

    private CsKgExpandMetricService service;

    @BeforeEach
    void setUp() {
        service = new CsKgExpandMetricService(metricMapper);
    }

    @Test
    void recordMapsFlagsToCounters() {
        service.record(88L, true, true);
        ArgumentCaptor<Integer> expanded = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> zeroHit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> zeroExpanded = ArgumentCaptor.forClass(Integer.class);
        verify(metricMapper).upsert(eq(88L), any(LocalDate.class),
            expanded.capture(), zeroHit.capture(), zeroExpanded.capture());
        assertEquals(1, expanded.getValue());
        assertEquals(1, zeroHit.getValue());
        assertEquals(1, zeroExpanded.getValue(), "扩展了但仍零命中,是评估扩展价值的关键行");

        service.record(88L, false, false);
        verify(metricMapper).upsert(eq(88L), any(LocalDate.class), eq(0), eq(0), eq(0));
    }

    @Test
    void emptyWorkspaceSentinelIsSkipped() {
        service.record(-1L, true, true);
        service.record(null, true, true);
        verify(metricMapper, never()).upsert(anyLong(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void upsertFailureIsSwallowed() {
        when(metricMapper.upsert(anyLong(), any(), anyInt(), anyInt(), anyInt()))
            .thenThrow(new RuntimeException("db down"));
        service.record(88L, true, false);
        assertTrue(true, "指标是旁路,写失败不能抛到检索链路");
    }

    @Test
    void listClampsWindowAndDelegates() {
        when(metricMapper.listRecent(88L, 90)).thenReturn(List.of());
        service.list(88L, 500);
        verify(metricMapper).listRecent(88L, 90);

        service.list(-1L, 7);
        verify(metricMapper, org.mockito.Mockito.times(1)).listRecent(anyLong(), anyInt());
    }
}
