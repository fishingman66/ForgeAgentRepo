package com.forgeagent.callcenter.gateway;

import com.forgeagent.callcenter.config.CallcenterProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FsGateway 单测(C1-real):验证真实网关的供应商路由、归一化复用与录音取回白名单。
 * 归一化断言与 FsMockGatewayTest 用同一 fixture,确保 Mock 与真实链路映射零差异。
 */
@Tag("dev")
class FsGatewayTest {

    private CallcenterProperties properties() {
        CallcenterProperties p = new CallcenterProperties();
        return p;
    }

    private FsGateway gateway(CallcenterProperties p) {
        return new FsGateway(p);
    }

    @Test
    void supportsOnlyRealFs() {
        FsGateway gateway = gateway(properties());
        assertTrue(gateway.supports("FS"));
        assertTrue(gateway.supports("fs"));
        assertFalse(gateway.supports("FS_MOCK"));
        assertFalse(gateway.supports("AICC"));
        assertFalse(gateway.supports(null));
    }

    @Test
    void verifyIsNoopForConnectionLevelAuth() {
        // ESL 认证由 starter 在连接阶段完成,verify 恒 true 仅满足契约
        assertTrue(gateway(properties()).verify(null, null, null, null, null));
    }

    @Test
    void parseReusesFsEventNormalizer() {
        String body = "{\"events\":[{\"eslEventName\":\"CHANNEL_CREATE\",\"eventGenTimeMs\":1794278400000,"
            + "\"workNo\":\"\",\"headers\":{\"Unique-ID\":\"FS-REAL-0001\","
            + "\"Caller-Caller-ID-Number\":\"13900000000\",\"Caller-Destination-Number\":\"1001\"}}]}";
        List<CsVoiceEvent> events = gateway(properties()).parse("FS", "CC_FS", body);
        assertEquals(1, events.size());
        assertEquals("call.ring", events.get(0).type());
        assertEquals("FS-REAL-0001", events.get(0).callId());
        assertEquals("13900000000", events.get(0).str("caller"));
    }

    @Test
    void fetchRecordingReadsLocalFile(@TempDir Path temp) throws Exception {
        Path dir = temp.resolve("recordings");
        Files.createDirectories(dir);
        Path wav = dir.resolve("call1.wav");
        byte[] payload = "RIFF-fake-wav".getBytes(StandardCharsets.US_ASCII);
        Files.write(wav, payload);

        CallcenterProperties p = properties();
        p.getFreeswitch().setRecordingDir(dir.toString());
        FsGateway gateway = gateway(p);

        assertArrayEqualsFast(payload, gateway.fetchRecording(wav.toString()));
        assertArrayEqualsFast(payload, gateway.fetchRecording("file://" + wav));
    }

    @Test
    void fetchRecordingRejectsPathTraversal(@TempDir Path temp) throws Exception {
        Path base = temp.resolve("allowed");
        Files.createDirectories(base);
        Path outside = temp.resolve("outside.wav");
        Files.write(outside, new byte[]{1, 2, 3});

        CallcenterProperties p = properties();
        p.getFreeswitch().setRecordingDir(base.toString());
        FsGateway gateway = gateway(p);

        assertThrows(IllegalArgumentException.class, () -> gateway.fetchRecording(outside.toString()));
        assertThrows(IllegalArgumentException.class, () -> gateway.fetchRecording("mock2://x"));
    }

    @Test
    void fetchRecordingSupportsMockPrefix() {
        assertEquals(16044, gateway(properties()).fetchRecording("mock://wav/8k-mono-1s").length);
    }

    @Test
    void fetchRecordingRejectsMissingFile(@TempDir Path temp) {
        assertThrows(IllegalStateException.class,
            () -> gateway(properties()).fetchRecording(temp.resolve("none.wav").toString()));
    }

    private static void assertArrayEqualsFast(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte at " + i);
        }
    }
}
