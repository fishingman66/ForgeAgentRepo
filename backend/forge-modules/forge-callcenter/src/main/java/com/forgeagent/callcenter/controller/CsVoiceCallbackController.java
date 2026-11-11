package com.forgeagent.callcenter.controller;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.service.CallEventDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AICC 语音回调入口(V1,免登录,鉴权走验签 + 时间窗 + nonce)。
 *
 * <p>响应固定 AICC 格式 {@code {message, retcode}} 且 HTTP 200,绝不抛出异常:
 * 项目全局异常处理器会包装成 R&lt;T&gt;,语音侧不适用(方案 v1 7.1 / v2.1 13.3)。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
@RequestMapping("/chat-voice")
public class CsVoiceCallbackController {

    private final CallEventDispatcher dispatcher;

    @PostMapping("/{provider}/{code}")
    public Map<String, String> onCallback(@PathVariable String provider,
                                          @PathVariable String code,
                                          @RequestHeader(value = "X-Cc-Timestamp", required = false) String timestamp,
                                          @RequestHeader(value = "X-Cc-Nonce", required = false) String nonce,
                                          @RequestHeader(value = "X-Cc-Signature", required = false) String signature,
                                          @RequestBody String rawBody) {
        try {
            return dispatcher.dispatch(provider, code, timestamp, nonce, signature, rawBody);
        } catch (Exception e) {
            log.error("[CC] 回调处理异常 code={}", code, e);
            return CallEventDispatcher.REJECTED;
        }
    }
}
