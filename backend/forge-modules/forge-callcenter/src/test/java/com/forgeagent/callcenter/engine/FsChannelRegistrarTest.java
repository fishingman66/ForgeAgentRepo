package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.CallcenterProperties;
import link.thingscloud.freeswitch.esl.InboundClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FsChannelRegistrar 单测(C1-real):验证接通登记 / 挂机注销 / 非 FS 跳过 / ESL 缺失降级。
 * 这是 P2 转人工真实生效的关键连接件。
 */
@Tag("dev")
class FsChannelRegistrarTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<InboundClient> provider(InboundClient client) {
        ObjectProvider<InboundClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);
        return p;
    }

    private CallcenterProperties properties() {
        CallcenterProperties p = new CallcenterProperties();
        p.getFreeswitch().setEslAddress("127.0.0.1:8021");
        return p;
    }

    @Test
    void registersChannelOnAnswerForFs() {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        FsChannelRegistrar registrar = new FsChannelRegistrar(
            provider(mock(InboundClient.class)), registry, properties());

        registrar.onAnswered("FS", "CALL-1");
        assertNotNull(registry.find("CALL-1"));
        assertEquals(1, registry.size());
    }

    @Test
    void skipsNonFsProvider() {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        FsChannelRegistrar registrar = new FsChannelRegistrar(
            provider(mock(InboundClient.class)), registry, properties());

        registrar.onAnswered("AICC", "CALL-2");
        registrar.onAnswered("FS_MOCK", "CALL-3");
        assertEquals(0, registry.size());
    }

    @Test
    void skipsWhenEslClientMissing() {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        FsChannelRegistrar registrar = new FsChannelRegistrar(provider(null), registry, properties());

        registrar.onAnswered("FS", "CALL-4");
        assertEquals(0, registry.size());
    }

    @Test
    void unregistersOnEnded() {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        FsChannelRegistrar registrar = new FsChannelRegistrar(
            provider(mock(InboundClient.class)), registry, properties());

        registrar.onAnswered("FS", "CALL-5");
        assertNotNull(registry.find("CALL-5"));
        registrar.onEnded("CALL-5");
        assertNull(registry.find("CALL-5"));
        assertEquals(0, registry.size());
    }

    @Test
    void duplicateAnswerKeepsSingleChannel() {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        FsChannelRegistrar registrar = new FsChannelRegistrar(
            provider(mock(InboundClient.class)), registry, properties());

        registrar.onAnswered("FS", "CALL-6");
        ChannelControl first = registry.find("CALL-6");
        registrar.onAnswered("FS", "CALL-6");
        // 幂等:同一个通道实例保留,不被覆盖
        assertEquals(first, registry.find("CALL-6"));
        assertEquals(1, registry.size());
    }

    @Test
    void endedOnUnknownCallIsNoop() {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        FsChannelRegistrar registrar = new FsChannelRegistrar(
            provider(mock(InboundClient.class)), registry, properties());
        registrar.onEnded("NOT-EXIST");
        assertEquals(0, registry.size());
    }

    @Test
    void answerRequestsMediaStreamStart() {
        InboundClient client = mock(InboundClient.class);
        when(client.sendAsyncApiCommand(anyString(), anyString(), anyString())).thenReturn("+OK");
        LiveChannelRegistry registry = new LiveChannelRegistry();
        FsChannelRegistrar registrar = new FsChannelRegistrar(provider(client), registry, properties());

        registrar.onAnswered("FS", "CALL-7");

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(client).sendAsyncApiCommand(anyString(), cmd.capture(), args.capture());
        assertEquals("uuid_audio_stream", cmd.getValue());
        assertEquals("CALL-7 start ws://127.0.0.1:8080/cc/media/CALL-7 mono 8k", args.getValue());
    }

    @Test
    void mediaStartSkippedWhenBaseMissing() {
        InboundClient client = mock(InboundClient.class);
        CallcenterProperties p = properties();
        p.getFreeswitch().setMediaWsBase("");
        LiveChannelRegistry registry = new LiveChannelRegistry();
        FsChannelRegistrar registrar = new FsChannelRegistrar(provider(client), registry, p);

        registrar.onAnswered("FS", "CALL-8");

        // 只登记通道,不下发媒体命令
        verify(client, org.mockito.Mockito.never())
            .sendAsyncApiCommand(anyString(), anyString(), anyString());
        assertEquals(1, registry.size());
    }
}
