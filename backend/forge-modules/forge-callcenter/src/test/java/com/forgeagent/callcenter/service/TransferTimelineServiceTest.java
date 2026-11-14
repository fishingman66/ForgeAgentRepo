package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C8 转接时间轴:同一通电话只登记一条 RING / CONNECT,段类型与说话人固定。 */
@Tag("dev")
class TransferTimelineServiceTest {

    private CsCallSegmentMapper segmentMapper;
    private TransferTimelineService service;

    @BeforeEach
    void setUp() {
        segmentMapper = mock(CsCallSegmentMapper.class);
        service = new TransferTimelineService(segmentMapper);
    }

    @Test
    void ringSegmentIsWrittenOnce() {
        when(segmentMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        CsCall call = call();

        service.recordRing(call, event(Map.of("transferTo", "after_sales")));

        ArgumentCaptor<CsCallSegment> captor = ArgumentCaptor.forClass(CsCallSegment.class);
        verify(segmentMapper).insert(captor.capture());
        CsCallSegment segment = captor.getValue();
        assertEquals(TransferTimelineService.KIND_RING, segment.getSegmentKind());
        assertEquals("system", segment.getSpeaker(), "时间轴段不能进主叫轮次统计");
        assertTrue(segment.getText().contains("after_sales"), segment.getText());
    }

    @Test
    void duplicateTimelineSegmentIsSkipped() {
        when(segmentMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        service.recordConnect(call(), event(Map.of("state", "READY")));

        verify(segmentMapper, never()).insert(any(CsCallSegment.class));
    }

    @Test
    void connectTextCarriesWorkNo() {
        when(segmentMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.recordConnect(call(), event(Map.of("state", "READY")));

        ArgumentCaptor<CsCallSegment> captor = ArgumentCaptor.forClass(CsCallSegment.class);
        verify(segmentMapper).insert(captor.capture());
        assertEquals(TransferTimelineService.KIND_CONNECT, captor.getValue().getSegmentKind());
        assertTrue(captor.getValue().getText().contains("SEAT-001"));
    }

    @Test
    void seatStateKeyIsBackwardCompatible() {
        assertEquals("READY", TransferTimelineService.seatState(event(Map.of("readyState", "READY"))),
            "AICC 路径用 readyState");
        assertEquals("TALKING", TransferTimelineService.seatState(event(Map.of("state", "TALKING"))),
            "FS 归一化用 state");
        assertEquals("READY", TransferTimelineService.seatState(
            event(Map.of("readyState", "READY", "state", "TALKING"))), "两键并存时以 AICC 口径优先");
    }

    @Test
    void missingCallIdIsIgnored() {
        CsCall call = new CsCall();
        service.recordRing(call, event(Map.of("transferTo", "after_sales")));
        verify(segmentMapper, times(0)).insert(any(CsCallSegment.class));
    }

    private static CsCall call() {
        CsCall call = new CsCall();
        call.setId(9L);
        call.setCallId("FS-CALL-1");
        return call;
    }

    private static CsVoiceEvent event(Map<String, Object> content) {
        return new CsVoiceEvent("FS_MOCK", "FS_MOCK_CH1", "raw", "call.transfer",
            "SEAT-001", 1794278409000L, content);
    }
}
