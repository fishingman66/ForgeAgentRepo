package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.CallcenterProperties;
import link.thingscloud.freeswitch.esl.InboundClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FsChannelControl 单测(C1-real):mock InboundClient,断言下发的 ESL 命令拼装正确。
 * 重点覆盖转人工的队列名->号码映射与 context,这是 P2 的核心链路。
 */
@Tag("dev")
class FsChannelControlTest {

    private static final String ADDR = "127.0.0.1:8021";
    private static final String UUID = "FS-UUID-0001";

    private CallcenterProperties.FreeswitchProperties config() {
        CallcenterProperties.FreeswitchProperties c = new CallcenterProperties.FreeswitchProperties();
        c.setEslAddress(ADDR);
        c.setRecordingDir("/var/lib/freeswitch/recordings");
        c.setDialplanContext("default");
        c.setQueueExtensions("after_sales:5000, vip:5001");
        return c;
    }

    private InboundClient client() {
        InboundClient client = mock(InboundClient.class);
        when(client.sendAsyncApiCommand(anyString(), anyString(), anyString())).thenReturn("+OK");
        return client;
    }

    @Test
    void answerSendsUuidAnswer() {
        InboundClient client = client();
        new FsChannelControl(client, ADDR, UUID, config()).answer();
        verify(client).sendAsyncApiCommand(eq(ADDR), eq("uuid_answer"), eq(UUID));
    }

    @Test
    void transferMapsQueueNameToDialplanExtension() {
        InboundClient client = client();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        new FsChannelControl(client, ADDR, UUID, config()).transferToQueue("after_sales");
        verify(client).sendAsyncApiCommand(eq(ADDR), eq("uuid_transfer"), args.capture());
        assertEquals(UUID + " 5000 XML default", args.getValue());
    }

    @Test
    void transferFallsBackToQueueNameWhenUnmapped() {
        InboundClient client = client();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        new FsChannelControl(client, ADDR, UUID, config()).transferToQueue("unknown_queue");
        verify(client).sendAsyncApiCommand(eq(ADDR), eq("uuid_transfer"), args.capture());
        assertEquals(UUID + " unknown_queue XML default", args.getValue());
    }

    @Test
    void transferIsCaseInsensitiveOnQueueName() {
        InboundClient client = client();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        new FsChannelControl(client, ADDR, UUID, config()).transferToQueue("AFTER_SALES");
        verify(client).sendAsyncApiCommand(eq(ADDR), eq("uuid_transfer"), args.capture());
        assertEquals(UUID + " 5000 XML default", args.getValue());
    }

    @Test
    void hangupDefaultsToNormalClearing() {
        InboundClient client = client();
        FsChannelControl ch = new FsChannelControl(client, ADDR, UUID, config());
        ch.hangup(null);
        verify(client).sendAsyncApiCommand(eq(ADDR), eq("uuid_kill"), eq(UUID + " NORMAL_CLEARING"));
        ch.hangup("USER_BUSY");
        verify(client).sendAsyncApiCommand(eq(ADDR), eq("uuid_kill"), eq(UUID + " USER_BUSY"));
    }

    @Test
    void startRecordingWritesWavUnderConfiguredDir() {
        InboundClient client = client();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        new FsChannelControl(client, ADDR, UUID, config()).startRecording("call-rec");
        verify(client).sendAsyncApiCommand(eq(ADDR), eq("uuid_record"), args.capture());
        assertEquals(UUID + " start /var/lib/freeswitch/recordings/call-rec.wav", args.getValue());
    }

    @Test
    void interruptSendsUuidBreakAll() {
        InboundClient client = client();
        new FsChannelControl(client, ADDR, UUID, config()).interruptPlayback();
        verify(client).sendAsyncApiCommand(eq(ADDR), eq("uuid_break"), eq(UUID + " all"));
    }

    @Test
    void playAudioIsNoopBeforeMediaPlane() {
        InboundClient client = client();
        // P2 无媒体面:playAudio 不应下发任何命令
        new FsChannelControl(client, ADDR, UUID, config()).playAudio(new byte[]{1, 2, 3});
        verify(client, org.mockito.Mockito.never())
            .sendAsyncApiCommand(anyString(), anyString(), anyString());
    }

    @Test
    void commandFailureIsSwallowed() {
        InboundClient client = mock(InboundClient.class);
        when(client.sendAsyncApiCommand(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("esl down"));
        // 不外抛即通过
        new FsChannelControl(client, ADDR, UUID, config()).answer();
        assertTrue(true);
    }
}
