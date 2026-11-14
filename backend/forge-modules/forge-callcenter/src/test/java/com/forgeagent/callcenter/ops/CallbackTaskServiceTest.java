package com.forgeagent.callcenter.ops;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallbackTask;
import com.forgeagent.callcenter.mapper.CsCallbackTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C8 呼损回拨:判定口径(有振铃无接听)、建单幂等、状态机单向。 */
@Tag("dev")
class CallbackTaskServiceTest {

    private CsCallbackTaskMapper taskMapper;
    private CallbackTaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(CsCallbackTaskMapper.class);
        service = new CallbackTaskService(taskMapper);
        when(taskMapper.insert(any(CsCallbackTask.class))).thenReturn(1);
    }

    @Test
    void ringingWithoutAnswerCreatesTask() {
        when(taskMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertTrue(service.onHangup(call(new Date(), null)));

        ArgumentCaptor<CsCallbackTask> captor = ArgumentCaptor.forClass(CsCallbackTask.class);
        verify(taskMapper).insert(captor.capture());
        CsCallbackTask task = captor.getValue();
        assertEquals(CsCallbackTask.STATUS_PENDING, task.getStatus());
        assertEquals(CallbackTaskService.REASON_NO_ANSWER, task.getReason());
        assertEquals(0, task.getAttemptCount());
        assertEquals("138****5678", task.getCallerNumber());
    }

    @Test
    void duplicateHangupDoesNotCreateSecondTask() {
        when(taskMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertFalse(service.onHangup(call(new Date(), null)));
        verify(taskMapper, never()).insert(any(CsCallbackTask.class));
    }

    @Test
    void answeredOrUnringingCallIsNotMissed() {
        assertFalse(service.onHangup(call(new Date(), new Date())), "已接通不算呼损");
        assertFalse(service.onHangup(call(null, null)), "无振铃的脏数据不进清单");
        verify(taskMapper, never()).insert(any(CsCallbackTask.class));
    }

    @Test
    void transitionMatrixIsOneWay() {
        assertTrue(CallbackTaskService.canTransit("PENDING", "CALLED"));
        assertTrue(CallbackTaskService.canTransit("PENDING", "CLOSED"));
        assertTrue(CallbackTaskService.canTransit("CALLED", "CLOSED"));
        assertTrue(CallbackTaskService.canTransit("CALLED", "CALLED"), "重复拨打只累加尝试次数");
        assertFalse(CallbackTaskService.canTransit("CALLED", "PENDING"), "不能退回待办,避免重复外呼");
        assertFalse(CallbackTaskService.canTransit("CLOSED", "CALLED"));
        assertFalse(CallbackTaskService.canTransit(null, "CALLED"));
    }

    @Test
    void markCalledIncrementsAttempt() {
        CsCallbackTask task = new CsCallbackTask();
        task.setId(5L);
        task.setWorkspaceId(1L);
        task.setStatus(CsCallbackTask.STATUS_PENDING);
        when(taskMapper.selectById(5L)).thenReturn(task);

        CsCallbackTask updated = service.mark(5L, "called", 77L, "已回拨,客户未接");

        assertEquals(CsCallbackTask.STATUS_CALLED, updated.getStatus(), "入参大小写不敏感");
        assertEquals(1, updated.getAttemptCount());
        assertNotNull(updated.getLastAttemptAt());
        assertEquals(77L, updated.getOperatorId());
        verify(taskMapper, times(1)).updateById(task);
    }

    @Test
    void illegalTransitionIsRejected() {
        CsCallbackTask task = new CsCallbackTask();
        task.setId(5L);
        task.setStatus(CsCallbackTask.STATUS_CLOSED);
        when(taskMapper.selectById(5L)).thenReturn(task);

        assertThrows(IllegalStateException.class, () -> service.mark(5L, "PENDING", 77L, null));
        verify(taskMapper, never()).updateById(any(CsCallbackTask.class));
    }

    @Test
    void unknownStatusAndMissingTaskAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.mark(5L, "DONE", 77L, null));
        when(taskMapper.selectById(9L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.mark(9L, "CALLED", 77L, null));
    }

    private static CsCall call(Date ringAt, Date answerAt) {
        CsCall call = new CsCall();
        call.setId(9L);
        call.setCallId("FS-CALL-1");
        call.setWorkspaceId(1L);
        call.setChannelCode("FS_MOCK_CH1");
        call.setCallerNumber("138****5678");
        call.setRingAt(ringAt);
        call.setAnswerAt(answerAt);
        return call;
    }
}
