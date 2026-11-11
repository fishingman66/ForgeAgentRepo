package com.forgeagent.callcenter.storage;

import com.forgeagent.callcenter.common.CsChannelSignature;
import com.forgeagent.callcenter.config.CallcenterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 本地文件系统实现(V1,开发环境无真实 OSS 时使用)。
 *
 * <p>对象写 {@code agent.callcenter.recording-local-dir}(默认 ~/.forgeagent/cc-recordings),
 * 键规则与 OSS 完全一致;下载走本模块 {@code /chat-voice/storage} 端点,
 * 带 expires + HMAC 签名(与 13.2 同风格),并做路径穿越防护。</p>
 */
@Slf4j
public class LocalFsRecordingStorage implements RecordingStorage {

    private final Path root;
    private final String secret;

    public LocalFsRecordingStorage(CallcenterProperties properties) {
        this.root = Paths.get(properties.getRecordingLocalDir()).toAbsolutePath().normalize();
        this.secret = properties.getCallbackSecret();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("录音本地目录创建失败: " + root, e);
        }
        log.info("[CC] 录音走本地文件系统存储: {}", root);
    }

    @Override
    public void save(String objectKey, byte[] data) {
        Path target = resolveSafe(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new IllegalStateException("录音本地写入失败: " + objectKey, e);
        }
    }

    @Override
    public boolean delete(String objectKey) {
        try {
            return Files.deleteIfExists(resolveSafe(objectKey));
        } catch (IOException e) {
            log.warn("[CC] 录音本地删除失败 key={}: {}", objectKey, e.getMessage());
            return false;
        }
    }

    @Override
    public String presignReadUrl(String objectKey, Duration ttl) {
        long expires = Instant.now().getEpochSecond() + ttl.toSeconds();
        return currentBaseUrl() + "/chat-voice/storage?key=" + URLEncoder.encode(objectKey, StandardCharsets.UTF_8)
            + "&expires=" + expires + "&signature=" + sign(expires, objectKey);
    }

    /** 下载端点专用:校验签名与有效期,通过后返回文件流;失败一律返回 null(模糊拒绝)。 */
    public InputStream openSigned(String objectKey, long expires, String signature) {
        if (expires < Instant.now().getEpochSecond()) {
            return null;
        }
        if (!CsChannelSignature.constantTimeEquals(sign(expires, objectKey), signature)) {
            return null;
        }
        try {
            return Files.newInputStream(resolveSafe(objectKey));
        } catch (IOException e) {
            return null;
        }
    }

    /** 路径穿越防护:对象键规范化后必须仍位于根目录内。 */
    private Path resolveSafe(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("对象键为空");
        }
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法对象键: " + objectKey);
        }
        return target;
    }

    private String sign(long expires, String objectKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = expires + "\n" + objectKey;
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("录音签名计算失败", e);
        }
    }

    private String currentBaseUrl() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            String base = request.getScheme() + "://" + request.getServerName();
            if (request.getServerPort() != 80 && request.getServerPort() != 443) {
                base += ":" + request.getServerPort();
            }
            return base + request.getContextPath();
        }
        return "http://127.0.0.1:8080";
    }
}
