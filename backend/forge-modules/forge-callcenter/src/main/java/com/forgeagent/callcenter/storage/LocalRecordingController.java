package com.forgeagent.callcenter.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Map;

/**
 * 本地录音下载端点(V1,仅 recording-store=local-fs 时装配)。
 *
 * <p>免登录但带 expires + HMAC 签名;拒绝时不区分过期与签名错误,不泄露对象是否存在。
 * 生产 oss 模式不装配本端点,访问走 OSS presignGetUrl。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat-voice/storage")
public class LocalRecordingController {

    private final LocalFsRecordingStorage storage;

    @GetMapping
    public ResponseEntity<Object> read(@RequestParam String key,
                                       @RequestParam long expires,
                                       @RequestParam String signature) {
        InputStream stream = storage.openSigned(key, expires, signature);
        if (stream == null) {
            return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", "not available", "retcode", "1002"));
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/wav"))
            .body(new InputStreamResource(stream));
    }
}
