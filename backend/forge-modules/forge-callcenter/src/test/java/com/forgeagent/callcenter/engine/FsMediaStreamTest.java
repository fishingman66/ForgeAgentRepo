package com.forgeagent.callcenter.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FsMediaStream 单测(C1-real):验证上行回调、下行 streamAudio 编码、连接前缓冲补发、
 * clearAudio 打断。用内存 AudioSocket 替身,不启 WebSocket 容器。
 */
@Tag("dev")
class FsMediaStreamTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 内存 socket 替身:记录发出的文本帧。 */
    static final class RecordingSocket implements FsMediaStream.AudioSocket {
        final List<String> texts = new ArrayList<>();
        boolean closed;
        boolean open = true;

        @Override
        public void sendText(String text) {
            texts.add(text);
        }

        @Override
        public void close() {
            closed = true;
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    @Test
    void callerFrameGoesToListener() {
        FsMediaStream stream = new FsMediaStream("CALL-1");
        List<byte[]> received = new ArrayList<>();
        stream.onCallerAudio(received::add);

        stream.onCallerFrame(new byte[]{1, 2, 3});
        assertEquals(1, received.size());
        assertEquals(3, received.get(0).length);
    }

    @Test
    void writeTtsEncodesStreamAudioJson() throws Exception {
        FsMediaStream stream = new FsMediaStream("CALL-2");
        RecordingSocket socket = new RecordingSocket();
        stream.attach(socket);

        byte[] pcm = new byte[]{10, 20, 30, 40};
        stream.writeTts(pcm);

        assertEquals(1, socket.texts.size());
        JsonNode json = mapper.readTree(socket.texts.get(0));
        assertEquals("streamAudio", json.path("type").asText());
        assertEquals("raw", json.path("data").path("audioDataType").asText());
        assertEquals(8000, json.path("data").path("sampleRate").asInt());
        assertEquals(Base64.getEncoder().encodeToString(pcm),
            json.path("data").path("audioData").asText());
    }

    @Test
    void buffersBeforeAttachAndFlushesOnAttach() {
        FsMediaStream stream = new FsMediaStream("CALL-3");
        stream.writeTts(new byte[]{1, 2});
        stream.writeTts(new byte[]{3, 4});

        RecordingSocket socket = new RecordingSocket();
        stream.attach(socket);

        // 两块的补发 + 期间无新写
        assertEquals(2, socket.texts.size());
    }

    @Test
    void interruptSendsClearAudio() {
        FsMediaStream stream = new FsMediaStream("CALL-4");
        RecordingSocket socket = new RecordingSocket();
        stream.attach(socket);
        stream.writeTts(new byte[1600]);
        assertTrue(stream.isBotPlaying());

        stream.interruptPlayback();
        assertFalse(stream.isBotPlaying());
        assertTrue(socket.texts.contains("{\"type\":\"clearAudio\"}"));
    }

    @Test
    void isBotPlayingExpiresAfterEstimatedDuration() {
        FsMediaStream stream = new FsMediaStream("CALL-5");
        stream.attach(new RecordingSocket());
        // 8000 字节 = 0.5 秒
        stream.writeTts(new byte[8000]);
        assertTrue(stream.isBotPlaying());
    }

    @Test
    void closedStreamIgnoresWrites() {
        FsMediaStream stream = new FsMediaStream("CALL-6");
        RecordingSocket socket = new RecordingSocket();
        stream.attach(socket);
        stream.close();
        stream.writeTts(new byte[]{1});
        assertTrue(socket.closed);
    }

    @Test
    void detachThenWriteIsBufferedNotLost() {
        FsMediaStream stream = new FsMediaStream("CALL-7");
        RecordingSocket first = new RecordingSocket();
        stream.attach(first);
        stream.detach();
        stream.writeTts(new byte[]{9});

        RecordingSocket second = new RecordingSocket();
        stream.attach(second);
        assertEquals(1, second.texts.size());
    }
}
