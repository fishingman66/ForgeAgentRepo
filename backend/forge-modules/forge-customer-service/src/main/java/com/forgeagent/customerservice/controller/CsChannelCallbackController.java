package com.forgeagent.customerservice.controller;

import com.forgeagent.customerservice.channel.CsChannelAdapter;
import com.forgeagent.customerservice.channel.CsInboundMessage;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.service.CsChannelDispatchService;
import com.forgeagent.customerservice.service.CsChannelGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 渠道回调(C3):GET 用于公众号/企微 URL 验证,POST 接收上行消息。
 *
 * <p>{@code /chat-channel/**} 由 security.excludes 放行,但验签、时间窗与消息幂等
 * 均在服务端完成;验签失败统一返回 403,不泄露渠道是否存在。</p>
 */
@Slf4j
@ConditionalOnCustomerServiceEnabled
@RequiredArgsConstructor
@RestController
@RequestMapping("/chat-channel")
public class CsChannelCallbackController {

    private final List<CsChannelAdapter> adapters;
    private final CsChannelGuard guard;
    private final CsChannelDispatchService dispatch;
    private final CsModuleProperties properties;

    @GetMapping(value = "/{type}/{code}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@PathVariable String type, @PathVariable String code,
                                         @RequestParam Map<String, String> params) {
        if (!verified(type, code, params)) {
            return ResponseEntity.status(403).body("fail");
        }
        CsChannelAdapter adapter = adapter(type);
        String echo = adapter == null ? null : adapter.echo(params);
        return ResponseEntity.ok(echo == null ? "" : echo);
    }

    @PostMapping(value = "/{type}/{code}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receive(@PathVariable String type, @PathVariable String code,
                                          @RequestParam Map<String, String> params,
                                          @RequestBody(required = false) String body) {
        if (!verified(type, code, params)) {
            return ResponseEntity.status(403).body("fail");
        }
        CsChannelAdapter adapter = adapter(type);
        if (adapter == null) {
            return ResponseEntity.status(403).body("fail");
        }
        CsInboundMessage inbound = adapter.parse(body, params);
        try {
            String reply = dispatch.handle(guard.require(code), inbound);
            return ResponseEntity.ok(adapter.formatReply(inbound, reply));
        } catch (Exception e) {
            // 对外只回通用文案,细节留在服务端日志,避免渠道侧探测内部错误
            log.warn("[CS] 渠道消息处理失败 type={} code={}: {}", type, code, e.getMessage());
            return ResponseEntity.ok(adapter.formatReply(inbound, "服务暂时不可用,请稍后再试"));
        }
    }

    private boolean verified(String type, String code, Map<String, String> params) {
        CsChannelAdapter adapter = adapter(type);
        if (adapter == null) {
            return false;
        }
        try {
            CsChannel channel = guard.require(code);
            if (!adapter.verify(channel, params, properties)) {
                return false;
            }
            // C4:nonce 短期去重,防止同一验签参数被重放(消息级幂等由 MsgId 兜底)
            guard.checkNonce(code, params.get("nonce"));
            return true;
        } catch (Exception e) {
            log.warn("[CS] 渠道验签异常 type={} code={}: {}", type, code, e.getMessage());
            return false;
        }
    }

    private CsChannelAdapter adapter(String type) {
        return adapters.stream().filter(a -> a.type().equalsIgnoreCase(type)).findFirst().orElse(null);
    }
}
