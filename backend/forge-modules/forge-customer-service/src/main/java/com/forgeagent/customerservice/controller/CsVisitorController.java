package com.forgeagent.customerservice.controller;

import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.service.CsChannelGuard;
import com.forgeagent.customerservice.service.CsTicketService;
import com.forgeagent.customerservice.service.CsVisitorService;
import com.forgeagent.customerservice.transport.SseRealtimeTransport;
import com.forgeagent.agent.service.run.RunEventBuffer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访客端免登录 API(C0/C1)。{@code /chat-api/**} 由 security.excludes 放行,
 * 但所有请求仍经 CsChannelGuard 做 IP 预限流、渠道校验与访客令牌校验。
 */
@ConditionalOnCustomerServiceEnabled
@RequiredArgsConstructor
@RestController
@RequestMapping("/chat-api/{channelCode}")
public class CsVisitorController extends BaseController {

    private final CsChannelGuard guard;
    private final CsVisitorService visitorService;
    private final CsTicketService ticketService;
    private final RunEventBuffer runEventBuffer;
    /** C2:访客提交会话满意度。 */
    private final com.forgeagent.customerservice.service.CsatService csatService;
    /** C4:逐字流式对话。 */
    private final com.forgeagent.customerservice.service.CsStreamService streamService;
    /** P2 收尾:访客附件上传。 */
    private final com.forgeagent.customerservice.service.CsAttachmentService attachmentService;

    @PostMapping("/sessions")
    public R<Map<String, Object>> createSession(@PathVariable String channelCode,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        CsChannel channel = guard.require(channelCode);
        guard.checkRateLimit(channel, clientIp(request));
        guard.checkOrigin(channel, request.getHeader("Origin"));
        Map<String, Object> payload = body == null ? Map.of() : body;
        return R.ok(visitorService.createSession(channel,
            String.valueOf(payload.getOrDefault("externalUserId", ""))));
    }

    @PostMapping("/sessions/{cid}/messages")
    public R<Map<String, Object>> send(@PathVariable String channelCode, @PathVariable Long cid,
                                       @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        CsChannel channel = guard.require(channelCode);
        guard.checkRateLimit(channel, clientIp(request));
        guard.checkQuota(channel);
        guard.checkOrigin(channel, request.getHeader("Origin"));
        Long visitorId = visitorId(channelCode, request);
        return R.ok(visitorService.send(channel, cid, visitorId,
            str(body.get("clientMsgId")), str(body.get("content")), longIds(body.get("attachmentIds"))));
    }

    /**
     * 访客上传附件(P2 收尾):先开会话再上传,上传后把返回的 id 放进消息的 attachmentIds。
     *
     * <p>附件不进模型上下文,只落盘并关联会话,供坐席查看。</p>
     */
    @PostMapping("/sessions/{cid}/attachments")
    public R<com.forgeagent.agentcore.domain.vo.ChatAttachmentVo> uploadAttachment(
        @PathVariable String channelCode, @PathVariable Long cid,
        @org.springframework.web.bind.annotation.RequestPart("file")
        org.springframework.web.multipart.MultipartFile file,
        HttpServletRequest request) {
        CsChannel channel = guard.require(channelCode);
        guard.checkRateLimit(channel, clientIp(request));
        guard.checkOrigin(channel, request.getHeader("Origin"));
        Long visitorId = visitorId(channelCode, request);
        return R.ok(attachmentService.upload(channel, cid, visitorId, file));
    }

    /** body 里的 id 数组:兼容数字与字符串两种写法。 */
    private List<Long> longIds(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            try {
                ids.add(Long.parseLong(String.valueOf(item)));
            } catch (NumberFormatException ignored) {
                // 非法 id 直接跳过,后续归属校验会兜住
            }
        }
        return ids;
    }

    @PostMapping("/sessions/{cid}/handoff")
    public R<Map<String, Object>> handoff(@PathVariable String channelCode, @PathVariable Long cid,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        CsChannel channel = guard.require(channelCode);
        Long visitorId = visitorId(channelCode, request);
        Map<String, Object> payload = body == null ? Map.of() : body;
        return R.ok(visitorService.handoff(channel, cid, visitorId, str(payload.get("reason"))));
    }

    /** 事件重放:基于 RunEventBuffer 的单调序号,前端带 after 游标断线续传。 */
    @GetMapping("/sessions/{cid}/events")
    public R<List<Map<String, Object>>> events(@PathVariable String channelCode, @PathVariable Long cid,
                                               @RequestParam(defaultValue = "0") long after,
                                               HttpServletRequest request) {
        Long visitorId = visitorId(channelCode, request);
        visitorService.requireConversation(guard.require(channelCode), cid, visitorId);
        List<RunEventBuffer.RunEvent> raw = runEventBuffer.replay(SseRealtimeTransport.runKey(cid), after);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (RunEventBuffer.RunEvent e : raw) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", e.sequence());
            one.put("event", e.name());
            one.put("data", e.data());
            result.add(one);
        }
        return R.ok(result);
    }

    @PostMapping("/tickets/{ticketId}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String channelCode, @PathVariable Long ticketId,
                                          HttpServletRequest request) {
        Long visitorId = visitorId(channelCode, request);
        return R.ok(Map.of("status", ticketService.transition(ticketId, "RESOLVED", "CONFIRM",
            "VISITOR", visitorId, null).getStatus()));
    }

    @DeleteMapping("/sessions/{cid}")
    public R<Boolean> erase(@PathVariable String channelCode, @PathVariable Long cid,
                            HttpServletRequest request) {
        visitorService.erase(guard.require(channelCode), cid, visitorId(channelCode, request));
        return R.ok(true);
    }

    /** C2:访客提交会话满意度(1-5 分)。 */
    @PostMapping("/sessions/{cid}/csat")
    public R<Object> submitCsat(@PathVariable String channelCode, @PathVariable Long cid,
                                @RequestBody Map<String, Object> body,
                                HttpServletRequest request) {
        Long visitorId = visitorId(channelCode, request);
        Integer score;
        try {
            score = body.get("score") == null ? null : Integer.valueOf(String.valueOf(body.get("score")));
        } catch (NumberFormatException e) {
            throw new com.forgeagent.common.core.exception.ServiceException("评分须为 1 到 5 分");
        }
        return R.ok(csatService.submit(channelCode, cid, visitorId, score,
            str(body.get("comment")), str(body.get("tags"))));
    }

    /** C4:逐字流式发送消息(SSE),带 id: 帧并写入 RunEventBuffer 供断线续传。 */
    @PostMapping(value = "/sessions/{cid}/messages/stream",
        produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@PathVariable String channelCode, @PathVariable Long cid,
                       @RequestBody Map<String, Object> body,
                       jakarta.servlet.http.HttpServletRequest request,
                       jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        CsChannel channel = guard.require(channelCode);
        guard.checkRateLimit(channel, clientIp(request));
        guard.checkQuota(channel);
        guard.checkOrigin(channel, request.getHeader("Origin"));
        Long visitorId = visitorId(channelCode, request);
        response.setContentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        java.io.PrintWriter writer = response.getWriter();
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
        String runKey = com.forgeagent.customerservice.transport.SseRealtimeTransport.runKey(cid);
        com.forgeagent.agent.service.StreamEmitter emitter = new com.forgeagent.agent.service.StreamEmitter() {
            @Override
            public void send(String event, Object data) {
                String json = com.forgeagent.common.json.utils.JsonUtils.toJsonString(data);
                long id = seq.incrementAndGet();
                runEventBuffer.publish(runKey, event, json);
                writer.write("id: " + id + "\nevent: " + event + "\ndata: " + json + "\n\n");
                writer.flush();
            }

            @Override
            public void bindRun(String runId, Long sessionId) {
                writer.write("event: run_id\ndata: "
                    + com.forgeagent.common.json.utils.JsonUtils.toJsonString(Map.of("runId", runId)) + "\n\n");
                writer.flush();
            }
        };
        streamService.stream(channel, cid, visitorId,
            str(body.get("clientMsgId")), str(body.get("content")), emitter);
        writer.write("event: done\ndata: {}\n\n");
        writer.flush();
    }

    private Long visitorId(String channelCode, HttpServletRequest request) {
        String token = request.getHeader("X-Visitor-Token");
        if (token == null || token.isBlank()) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring(7);
            }
        }
        if (token == null || token.isBlank()) {
            throw new com.forgeagent.common.core.exception.ServiceException("缺少访客令牌");
        }
        return guard.verifyVisitorToken(token, channelCode);
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip == null ? "unknown" : ip.split(",")[0].trim();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
