package com.forgeagent.callcenter.storage;

import com.forgeagent.callcenter.config.CallcenterProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地录音存储单测(V1):存取、签名下载校验、路径穿越防护。
 */
@Tag("dev")
class LocalFsRecordingStorageTest {

    @TempDir
    Path tempDir;

    private LocalFsRecordingStorage storage() {
        CallcenterProperties properties = new CallcenterProperties();
        properties.setRecordingLocalDir(tempDir.toString());
        properties.setCallbackSecret("dev-only-aicc-mock-secret-2026");
        return new LocalFsRecordingStorage(properties);
    }

    @Test
    void saveAndDeleteRoundTrip() throws Exception {
        LocalFsRecordingStorage storage = storage();
        String key = "callcenter/recording/CL-1/REC-1.wav";
        byte[] data = new byte[]{1, 2, 3, 4};
        storage.save(key, data);
        Path file = tempDir.resolve(key);
        assertTrue(Files.exists(file));
        assertArrayEquals(data, Files.readAllBytes(file));
        assertTrue(storage.delete(key));
        assertFalse(Files.exists(file));
        assertFalse(storage.delete(key));
    }

    @Test
    void signedUrlGrantsAccessAndRejectsTampering() throws Exception {
        LocalFsRecordingStorage storage = storage();
        String key = "callcenter/recording/CL-1/REC-2.wav";
        storage.save(key, new byte[]{9, 9});
        String url = storage.presignReadUrl(key, Duration.ofSeconds(300));
        assertTrue(url.contains("/chat-voice/storage?key="));
        long expires = Long.parseLong(url.replaceAll(".*expires=(\\d+).*", "$1"));
        String signature = url.replaceAll(".*signature=([A-Za-z0-9_-]+).*", "$1");
        try (InputStream stream = storage.openSigned(key, expires, signature)) {
            assertNotNull(stream);
            assertTrue(stream.read() == 9);
        }
        assertNull(storage.openSigned(key, expires, "broken-signature"));
        assertNull(storage.openSigned(key, 1_000_000_000L, signature));
    }

    @Test
    void pathTraversalIsRejected() {
        LocalFsRecordingStorage storage = storage();
        assertThrows(IllegalArgumentException.class,
            () -> storage.save("../escape.wav", new byte[]{0}));
        assertThrows(IllegalArgumentException.class,
            () -> storage.save("a/../../escape.wav", new byte[]{0}));
        assertThrows(IllegalArgumentException.class, () -> storage.delete(null));
    }
}
