package com.forgeagent.callcenter.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通道控制 Mock 单测(C1):断言命令轨迹的顺序与内容——
 * 对话引擎(C2/C3)发出的下行命令必须可审计。
 */
@Tag("dev")
class MockChannelControlTest {

    @Test
    void commandsRecordedInOrder() {
        MockChannelControl control = new MockChannelControl("FS-CALL-1", 0);
        control.answer();
        control.playAudio(new byte[]{1, 2, 3});
        control.interruptPlayback();
        control.transferToQueue("after_sales");
        control.startRecording("cc/2026/11/FS-CALL-1.wav");
        control.hangup("NORMAL_CLEARING");

        List<MockChannelControl.Command> all = control.commands();
        assertEquals(6, all.size());
        assertEquals("answer", all.get(0).op());
        assertEquals("play", all.get(1).op());
        assertEquals(3, all.get(1).payloadBytes());
        assertEquals("interrupt", all.get(2).op());
        assertEquals("after_sales", all.get(3).arg());
        assertEquals("cc/2026/11/FS-CALL-1.wav", all.get(4).arg());
        assertEquals("hangup", all.get(5).op());
        assertEquals("NORMAL_CLEARING", all.get(5).arg());
        assertTrue(control.isHungUp());
        assertFalse(control.isPlaying());
    }

    @Test
    void filteredQueriesAndStateFlags() {
        MockChannelControl control = new MockChannelControl("FS-CALL-2", 0);
        control.answer();
        control.playAudio(new byte[]{1});
        control.playAudio(new byte[]{2, 2});
        assertEquals("FS-CALL-2", control.uuid());
        assertEquals(2, control.commandsOf("play").size());
        assertTrue(control.isAnswered());
        assertTrue(control.isPlaying());
        assertNotNull(control.lastPlayback());
        assertEquals(2, control.lastPlayback().length);
        assertFalse(control.isHungUp());
        // 时间轴单调:同批命令 atMs 非递减(轨迹可排时间线)
        List<MockChannelControl.Command> all = control.commands();
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i).atMs() >= all.get(i - 1).atMs());
        }
    }
}
