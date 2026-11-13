package com.forgeagent.callcenter.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FS_MOCK 网关单测(C1):报文用 mock/fs fixture 的真实文件,
 * 签名期望值由 fixture 调试脚本同一算法(Python)生成,跨语言一致;
 * 归一化断言与 mock/fs/sequence.json 的 eventTypeMap 逐条对齐。
 */
@Tag("dev")
class FsMockGatewayTest {

    private static final String SECRET = "dev-only-fs-mock-secret-2026";
    private static final String TIMESTAMP = "1794278400";
    private static final String NONCE = "fs-c1-java-vector";
    /** 由 Python hmac.new(SECRET, f"{TIMESTAMP}\n{NONCE}\n{RING_BODY}", sha256) 生成,Base64URL 无填充(temp/gen_fs_sig.py)。 */
    private static final String SIGNATURE = "Evits_vgDayGlySJJaDb2CNNI2m0sN3fuZ3jBwpI9tY";
    /** fixture 01-channel-create.json 的报文(单行化,内容与文件语义一致)。 */
    private static final String RING_BODY =
        "{\"events\":[{\"eslEventName\":\"CHANNEL_CREATE\",\"eventGenTimeMs\":1794278400000,\"workNo\":\"\","
            + "\"headers\":{\"Unique-ID\":\"FS-CALL-20261113-0001\",\"Caller-Caller-ID-Number\":\"13812345678\","
            + "\"Caller-Destination-Number\":\"1001\"}}]}";

    private final FsMockGateway gateway = new FsMockGateway();

    @Test
    void supportsOnlyFsMock() {
        assertTrue(gateway.supports("FS_MOCK"));
        assertTrue(gateway.supports("fs_mock"));
        // FS 标识需要 C1-real 的真实 FsGateway,Mock 不得顶替
        assertFalse(gateway.supports("FS"));
        assertFalse(gateway.supports("AICC"));
        assertFalse(gateway.supports(null));
    }

    @Test
    void verifyUsesSharedHmacAlgorithm() {
        assertTrue(gateway.verify(SECRET, TIMESTAMP, NONCE, RING_BODY, SIGNATURE));
        assertFalse(gateway.verify(SECRET, TIMESTAMP, NONCE, RING_BODY, SIGNATURE.substring(0, 20) + "X"));
        assertFalse(gateway.verify(null, TIMESTAMP, NONCE, RING_BODY, SIGNATURE));
        assertFalse(gateway.verify(SECRET, TIMESTAMP, NONCE, null, SIGNATURE));
    }

    @Test
    void verifyMatchesPythonReferenceVector() {
        // 与 AiccGatewayTest 同一算法口径,防止两套 fixture 签名漂移
        String expected = CsHmacSignatures.expected(SECRET, TIMESTAMP, NONCE, RING_BODY);
        assertEquals(SIGNATURE, expected);
    }

    @Test
    void parseMapsEslEventsToNormalizedNames() {
        List<CsVoiceEvent> events = gateway.parse("FS_MOCK", "FS_MOCK_CH1", RING_BODY);
        assertEquals(1, events.size());
        CsVoiceEvent ring = events.get(0);
        assertEquals("call.ring", ring.type());
        assertEquals("CHANNEL_CREATE", ring.rawType());
        assertEquals("FS-CALL-20261113-0001", ring.callId());
        assertEquals(1794278400000L, ring.eventGenTime());
        assertEquals("13812345678", ring.str("caller"));
        assertEquals("1001", ring.str("callee"));
        assertEquals("inbound", ring.str("direction"));
    }

    @Test
    void parseReplaysFullFixtureSequence() throws Exception {
        Path fixtureDir = Path.of("src/test/resources/mock/fs");
        assertTrue(Files.isDirectory(fixtureDir), "fixture 目录缺失:" + fixtureDir.toAbsolutePath());
        // 按序回放 11 个事件文件,断言归一化名称与 sequence.json 的推进语义一致
        String[] expectedTypes = {
            "call.ring", "call.answer", "call.asr_result", "call.bot_utterance", "call.asr_result",
            "call.dtmf", "call.transfer", "call.seat_state", "call.record.ready", "call.hangup", "call.cdr"};
        String[] files = {
            "01-channel-create.json", "02-channel-answer.json", "03-asr-result.json", "04-bot-utterance.json",
            "05-asr-result.json", "06-dtmf.json", "07-transfer.json", "08-agent-state.json",
            "09-record-ready.json", "10-channel-hangup-complete.json", "11-cdr.json"};
        for (int i = 0; i < files.length; i++) {
            String body = Files.readString(fixtureDir.resolve(files[i]), StandardCharsets.UTF_8);
            List<CsVoiceEvent> events = gateway.parse("FS_MOCK", "FS_MOCK_CH1", body);
            assertEquals(1, events.size(), files[i]);
            assertEquals(expectedTypes[i], events.get(0).type(), files[i]);
            assertEquals("FS-CALL-20261113-0001", events.get(0).callId(), files[i]);
        }
    }

    @Test
    void parseExtractsTransferAndHangupFields() throws Exception {
        Path fixtureDir = Path.of("src/test/resources/mock/fs");
        List<CsVoiceEvent> transfer = gateway.parse("FS_MOCK", "CH",
            Files.readString(fixtureDir.resolve("07-transfer.json"), StandardCharsets.UTF_8));
        assertEquals("after_sales", transfer.get(0).str("transferTo"));
        assertEquals("bot", transfer.get(0).str("transferFrom"));

        List<CsVoiceEvent> hangup = gateway.parse("FS_MOCK", "CH",
            Files.readString(fixtureDir.resolve("10-channel-hangup-complete.json"), StandardCharsets.UTF_8));
        assertEquals("NORMAL_CLEARING", hangup.get(0).str("hangupCause"));
        assertEquals("96", hangup.get(0).str("billSec"));

        List<CsVoiceEvent> seat = gateway.parse("FS_MOCK", "CH",
            Files.readString(fixtureDir.resolve("08-agent-state.json"), StandardCharsets.UTF_8));
        assertEquals("SEAT-001", seat.get(0).workNo());
        assertEquals("READY", seat.get(0).str("state"));
    }

    @Test
    void parseSkipsUnknownEslEventAndRejectsBadJson() {
        assertEquals(0, gateway.parse("FS_MOCK", "CH", "{\"events\":[]}").size());
        assertEquals(0, gateway.parse("FS_MOCK", "CH",
            "{\"events\":[{\"eslEventName\":\"HEARTBEAT\",\"headers\":{}}]}").size());
        assertThrows(IllegalArgumentException.class, () -> gateway.parse("FS_MOCK", "CH", "not-json"));
    }

    @Test
    void fetchRecordingReturnsFixedSilentWav() {
        byte[] bytes = gateway.fetchRecording("mock://wav/8k-mono-1s");
        assertEquals(16044, bytes.length);
        assertThrows(UnsupportedOperationException.class,
            () -> gateway.fetchRecording("/var/lib/freeswitch/recordings/1.wav"));
    }
}
