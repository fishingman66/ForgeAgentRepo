package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.ChatSendBo;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.agent.service.IAgentSessionService;
import com.forgeagent.agent.service.run.RunCancelRegistry;
import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.agent.service.run.RunStreamEmitter;
import com.forgeagent.agent.service.run.RunTraceService;
import com.forgeagent.agent.service.runner.AgentScopeRunner;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.redis.annotation.RateLimiter;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Agent 聊天 Controller
 *
 * <p>P0:流式端点统一走 {@link RunStreamEmitter},事件先落 Redis 缓冲再写 socket;
 * 断连后生成继续,客户端可带 {@code Last-Event-ID} 从 {@code /run/{runId}/events} 续传。</p>
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/chat")
public class AgentChatController extends BaseController {

    /** 重放连接最长跟随时间,防止异常终态造成连接悬挂 */
    private static final long REPLAY_MAX_WAIT_MS = Duration.ofMinutes(10).toMillis();
    private static final long REPLAY_POLL_MS = 300L;

    private final IAgentChatService chatService;
    private final AgentScopeRunner agentScopeRunner;
    private final IAgentApprovalService approvalService;
    private final IAgentSessionService sessionService;
    private final RunEventBuffer runEventBuffer;
    private final RunTraceService runTrace;
    private final RunCancelRegistry runCancel;

    /**
     * 发送消息(返回用户消息与助手回复)
     */
    @SaCheckPermission("agent:chat:send")
    @RateLimiter(key = "#{T(com.forgeagent.common.satoken.utils.LoginHelper).getUserId()}", time = 60, count = 10, message = "发送太频繁，请稍后再试")
    @Log(title = "聊天对话", businessType = BusinessType.INSERT)
    @PostMapping("/send")
    public R<ChatSendVo> send(@Validated @RequestBody ChatSendBo bo) {
        return R.ok(chatService.send(bo));
    }

    /**
     * 流式发送消息(SSE):事件序列 run_id → meta → delta* → usage → done,error 事件表示执行失败。
     * 同步写 Servlet 输出流保证 Content-Type 与增量到达;前端以 fetch + ReadableStream 消费
     * (EventSource 不支持 POST/自定义头)。
     */
    @SaCheckPermission("agent:chat:send")
    @RateLimiter(key = "#{T(com.forgeagent.common.satoken.utils.LoginHelper).getUserId()}", time = 60, count = 10, message = "发送太频繁，请稍后再试")
    @Log(title = "聊天对话", businessType = BusinessType.INSERT)
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@Validated @RequestBody ChatSendBo bo, HttpServletResponse response) throws IOException {
        RunStreamEmitter emitter = sseEmitter(response);
        try {
            chatService.streamSend(bo, emitter);
        } finally {
            emitter.finish();
        }
    }

    /**
     * 流式调试对话(UX-01):复用临时会话,按 draft 覆盖 Agent 配置执行;
     * 事件序列与 /stream 一致
     */
    @SaCheckPermission("agent:chat:send")
    @RateLimiter(key = "#{T(com.forgeagent.common.satoken.utils.LoginHelper).getUserId()}", time = 60, count = 20, message = "调试请求太频繁，请稍后再试")
    @Log(title = "Agent 调试对话", businessType = BusinessType.INSERT)
    @PostMapping(value = "/debug/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void debugStream(@Validated @RequestBody ChatSendBo bo, HttpServletResponse response) throws IOException {
        RunStreamEmitter emitter = sseEmitter(response);
        try {
            chatService.streamDebugSend(bo, emitter);
        } finally {
            emitter.finish();
        }
    }

    /**
     * 轮级中断:对执行中的会话发出 AgentScope 内核中断(下一个检查点由
     * handleInterrupt 收尾生成中断说明消息),同时取消该会话全部待审批单。
     */
    @SaCheckPermission("agent:chat:send")
    @RateLimiter(key = "#{T(com.forgeagent.common.satoken.utils.LoginHelper).getUserId()}", time = 60, count = 20, message = "操作过于频繁，请稍后再试")
    @Log(title = "聊天对话", businessType = BusinessType.OTHER)
    @PostMapping("/{sessionId}/interrupt")
    public R<String> interrupt(@NotNull(message = "会话ID不能为空")
                               @PathVariable Long sessionId) {
        // 校验会话归属,防止遍历 sessionId 中断他人对话
        sessionService.getOwned(sessionId);
        // P0/A3:写 Redis 取消令牌(跨实例可见),并立即在本实例触发一次内核中断
        String runId = runCancel.requestCancel(sessionId);
        boolean hit = agentScopeRunner.interrupt(sessionId);
        int cancelled = approvalService.cancelPendingBySession(sessionId);
        if (runId == null && !hit && cancelled == 0) {
            return R.warn("当前没有进行中的对话或待审批请求");
        }
        return R.ok("已发出中断指令,本轮对话即将结束", null);
    }

    /**
     * 重新生成最后一条助手回复:删除原回复后基于其前的用户消息重新执行(阻塞式)
     */
    @SaCheckPermission("agent:chat:send")
    @RateLimiter(key = "#{T(com.forgeagent.common.satoken.utils.LoginHelper).getUserId()}", time = 60, count = 5, message = "重生成太频繁，请稍后再试")
    @Log(title = "聊天对话", businessType = BusinessType.UPDATE)
    @PostMapping("/{sessionId}/regenerate")
    public R<ChatSendVo> regenerate(@NotNull(message = "会话ID不能为空")
                                    @PathVariable Long sessionId) {
        return R.ok(chatService.regenerate(sessionId));
    }

    /**
     * 流式重新生成最后一条助手回复(SSE):事件序列 run_id → delta* → done
     */
    @SaCheckPermission("agent:chat:send")
    @RateLimiter(key = "#{T(com.forgeagent.common.satoken.utils.LoginHelper).getUserId()}", time = 60, count = 5, message = "重生成太频繁，请稍后再试")
    @Log(title = "聊天对话", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/{sessionId}/regenerate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamRegenerate(@NotNull(message = "会话ID不能为空")
                                 @PathVariable Long sessionId,
                                 HttpServletResponse response) throws IOException {
        RunStreamEmitter emitter = sseEmitter(response);
        try {
            chatService.streamRegenerate(sessionId, emitter);
        } finally {
            emitter.finish();
        }
    }

    /**
     * SSE 断线续传(P0 / A1):先重放 {@code after} 之后的事件,再跟随运行中的新增事件,
     * 到达终态且无新事件时收口。重放前校验运行归属,避免跨用户读取对话内容。
     */
    @SaCheckPermission("agent:chat:send")
    @GetMapping(value = "/run/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void replayEvents(@PathVariable String runId,
                             @RequestParam(name = "after", defaultValue = "0") long after,
                             HttpServletResponse response) throws IOException {
        if (!isOwnedRun(runId)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "无权访问该运行记录");
            return;
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = response.getWriter();
        long cursor = Math.max(0L, after);
        long deadline = System.currentTimeMillis() + REPLAY_MAX_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            List<RunEventBuffer.RunEvent> events = runEventBuffer.replay(runId, cursor);
            for (RunEventBuffer.RunEvent event : events) {
                writer.write("id: " + event.sequence() + "\n");
                writer.write("event: " + event.name() + "\n");
                writer.write("data: " + oneLine(event.data()) + "\n\n");
                cursor = event.sequence();
            }
            writer.flush();
            if (writer.checkError()) {
                return;
            }
            boolean terminal = runEventBuffer.terminalOf(runId) != null;
            if (terminal && events.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(REPLAY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 运行链路详情(P0 / A2):主记录 + 按序步骤,供聊天消息「查看链路」下钻。
     */
    @SaCheckPermission("agent:chat:send")
    @GetMapping("/run/{runId}")
    public R<Map<String, Object>> runDetail(@PathVariable String runId) {
        if (!isOwnedRun(runId)) {
            return R.fail("无权查看该运行记录");
        }
        return R.ok(runTrace.detail(runId));
    }

    /**
     * 消息反馈(点赞/点踩):重复提交同值或传空值取消;点踩可携标签与备注;运营看板聚合反馈指标
     */
    @SaCheckPermission("agent:chat:send")
    @Log(title = "聊天对话", businessType = BusinessType.UPDATE)
    @PostMapping("/message/{messageId}/feedback")
    public R<Void> feedback(@NotNull(message = "消息ID不能为空")
                            @PathVariable Long messageId,
                            @RequestParam(required = false) String value,
                            @RequestParam(required = false) String comment,
                            @RequestParam(required = false) String tags) {
        chatService.feedback(messageId, value, comment, tags);
        return R.ok();
    }

    /**
     * 撤回最后一条用户消息:删除该消息及其后的助手回复(仅允许撤回会话末轮)
     */
    @SaCheckPermission("agent:chat:send")
    @Log(title = "聊天对话", businessType = BusinessType.DELETE)
    @DeleteMapping("/message/{messageId}")
    public R<Void> recallMessage(@NotNull(message = "消息ID不能为空")
                                 @PathVariable Long messageId) {
        chatService.recallMessage(messageId);
        return R.ok();
    }

    private RunStreamEmitter sseEmitter(HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return new RunStreamEmitter(response.getWriter(), runEventBuffer, runCancel);
    }

    private boolean isOwnedRun(String runId) {
        Long owner = runTrace.ownerOf(runId);
        Long current = LoginHelper.getUserId();
        return owner != null && current != null && owner.equals(current);
    }

    private static String oneLine(String payload) {
        if (payload == null) {
            return "";
        }
        return payload.replace("\r", "\\r").replace("\n", "\\n");
    }
}
