package com.forgeagent.customerservice.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsSeatSuggestion;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.service.CsAssistActionService;
import com.forgeagent.customerservice.service.CsAssistMetricService;
import com.forgeagent.customerservice.service.CsWorkspaceResolver;
import com.forgeagent.customerservice.service.SeatAssistService;
import com.forgeagent.customerservice.transport.SseRealtimeTransport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 坐席辅助端点(A1~A5):推荐拉取、采纳反馈、动作执行、会话事件流与度量。
 *
 * <p>实时化走「游标轮询重放」——与访客侧同一 RunEventBuffer 通道(单调序号 + after 断线续传),
 * 坐席带登录态订阅 {@code GET /conversations/{cid}/events?after=游标},增量拉取
 * SEAT_SUGGEST / CALL_TRANSCRIPT 等事件;workspace 边界经会话归属校验。</p>
 *
 * <p>A5 度量口径:采纳率按<b>推荐条数</b>算(采纳条数 / 推荐条数),动作次数单列。</p>
 */
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@ConditionalOnCustomerServiceEnabled
@RequestMapping("/agent/customerservice")
public class CsAssistController extends BaseController {

    private static final Set<String> FEEDBACK_ACTIONS = Set.of("send", "copy", "ignore");
    /** P3:SSE 轮询间隔、心跳间隔与单连接最长存活。 */
    private static final long STREAM_POLL_MS = 500L;
    private static final long STREAM_PING_MS = 15_000L;
    private static final long STREAM_MAX_WAIT_MS = 10 * 60 * 1000L;

    private final SeatAssistService seatAssistService;
    private final CsAssistActionService actionService;
    private final CsAssistMetricService metricService;
    private final com.forgeagent.customerservice.service.CsHandoffSummaryService summaryService;
    /** P2:成本口径与配额。 */
    private final com.forgeagent.customerservice.service.CsCostService costService;
    private final com.forgeagent.customerservice.service.CsQuotaService quotaService;
    /** P2 收尾:访客附件与死信。 */
    private final com.forgeagent.customerservice.service.CsAttachmentService attachmentService;
    private final com.forgeagent.customerservice.service.CsDeadLetterService deadLetterService;
    private final com.forgeagent.customerservice.mapper.CsSeatSuggestionMapper suggestionMapper;
    private final CsConversationMapper conversationMapper;
    private final CsWorkspaceResolver workspaceResolver;
    private final RunEventBuffer runEventBuffer;

    /** 某会话最新推荐(≤5 条):初始化历史,增量由 events 游标流承接。 */
    @GetMapping("/suggestions")
    public R<List<CsSeatSuggestion>> list(@RequestParam Long conversationId) {
        CsConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("会话不存在或无权访问");
        }
        return R.ok(seatAssistService.listByConversation(conversationId));
    }

    /** 采纳反馈:send=已填入输入框发送 / copy=复制 / ignore=忽略;从未采纳状态变更一次。 */
    @PostMapping("/suggestions/{id}/feedback")
    public R<Boolean> feedback(@PathVariable Long id, @RequestParam String action) {
        if (!FEEDBACK_ACTIONS.contains(action)) {
            return R.fail("非法反馈类型");
        }
        CsSeatSuggestion suggestion = suggestionMapper.selectById(id);
        if (suggestion == null) {
            return R.fail("推荐记录不存在");
        }
        CsConversation conv = conversationMapper.selectById(suggestion.getConversationId());
        if (conv == null || !conv.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("无权访问该推荐");
        }
        return R.ok(seatAssistService.feedback(id, action, conv.getWorkspaceId()));
    }

    /**
     * 建议动作执行(A4):REPLY / LINK / RESOLVE,只执行该推荐 actions 快照里的动作。
     * 前端必须先经坐席确认再调用本端点——服务端不提供「自动执行」入口。
     */
    @PostMapping("/suggestions/{id}/actions/{code}")
    public R<java.util.Map<String, Object>> executeAction(@PathVariable Long id,
                                                          @PathVariable String code) {
        return R.ok(actionService.execute(id, code, currentWorkspaceId(), LoginHelper.getUserId()));
    }

    /** 会话事件实时流(游标轮询重放):与访客侧同一缓冲通道,after 传上次最大序号,断线续传。 */
    @GetMapping("/conversations/{cid}/events")
    public R<List<java.util.Map<String, Object>>> events(@PathVariable Long cid,
                                                         @RequestParam(defaultValue = "0") long after) {
        CsConversation conv = conversationMapper.selectById(cid);
        if (conv == null || !conv.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("会话不存在或无权访问");
        }
        List<RunEventBuffer.RunEvent> raw =
            runEventBuffer.replay(SseRealtimeTransport.runKey(cid), after);
        List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (RunEventBuffer.RunEvent e : raw) {
            java.util.Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", e.sequence());
            one.put("event", e.name());
            one.put("data", e.data());
            result.add(one);
        }
        return R.ok(result);
    }

    /** 数据边界:与工作台其他端点同口径,坐席行缺失返回 -1 空空间。 */
    private Long currentWorkspaceId() {
        return workspaceResolver.currentWorkspaceId();
    }

    /* ---------------- A5 度量 ---------------- */

    /** 辅助看板:最近 N 天日指标 + 汇总(采纳率按推荐条数算)。 */
    @GetMapping("/assist/metrics")
    public R<java.util.Map<String, Object>> metrics(@RequestParam(defaultValue = "7") int days) {
        return R.ok(metricService.overview(currentWorkspaceId(), days));
    }

    /** 手动重算今日指标(看板刷新按钮)。 */
    @PostMapping("/assist/metrics/rebuild")
    public R<Object> rebuildMetrics() {
        return R.ok(metricService.rebuild(currentWorkspaceId(), new java.util.Date()));
    }

    /**
     * 零采纳回流:超期未采纳 + 同会话最近 3 条全未采纳的推荐,触发原文生成待审 FAQ。
     * hours=0 表示不限时间窗(补跑/验证用),默认 24 小时。
     */
    @PostMapping("/assist/recycle")
    public R<java.util.Map<String, Object>> recycle(@RequestParam(defaultValue = "24") int hours,
                                                    @RequestParam(defaultValue = "50") int limit) {
        return R.ok(metricService.recycle(currentWorkspaceId(), hours, limit));
    }

    /** 低质推荐追溯:最近未采纳、带触发原文的推荐。 */
    @GetMapping("/assist/badcases")
    public R<List<CsSeatSuggestion>> badcases(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(metricService.badcases(currentWorkspaceId(), limit));
    }

    /** 交接摘要(P0):坐席接单前先看这份结构化摘要。 */
    @GetMapping("/summaries")
    public R<java.util.Map<String, Object>> summary(@RequestParam Long ticketId) {
        return R.ok(summaryService.latest(currentWorkspaceId(), ticketId));
    }

    /**
     * 坐席实时事件流(P3):SSE 长连接替代 2 秒轮询。
     *
     * <p>与访客侧同一条 {@code RunEventBuffer} 通道,带 {@code after} 游标断线续传;
     * 每 15 秒发一次注释心跳保活,单连接最长 10 分钟(前端到期自动带游标重连)。
     * 会话不设终态,所以不按 terminal 收口,靠客户端断开与 deadline 退出。</p>
     */
    @cn.dev33.satoken.annotation.SaIgnore
    @GetMapping(value = "/conversations/{cid}/stream",
        produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@PathVariable Long cid,
                       @RequestParam(name = "after", defaultValue = "0") long after,
                       jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        // 类上 @SaCheckLogin 抛异常时全局处理器会往 event-stream 写 JSON 导致 500,
        // 这里显式校验并直接回 401,让前端能按"登录失效"处理。
        if (!cn.dev33.satoken.stp.StpUtil.isLogin()) {
            response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "未登录");
            return;
        }
        com.forgeagent.customerservice.domain.CsConversation conv = conversationMapper.selectById(cid);
        if (conv == null || !conv.getWorkspaceId().equals(currentWorkspaceId())) {
            response.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, "无权访问该会话");
            return;
        }
        response.setContentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        java.io.PrintWriter writer = response.getWriter();
        String runKey = com.forgeagent.customerservice.transport.SseRealtimeTransport.runKey(cid);
        long cursor = Math.max(0L, after);
        long deadline = System.currentTimeMillis() + STREAM_MAX_WAIT_MS;
        long lastWrite = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            java.util.List<com.forgeagent.agent.service.run.RunEventBuffer.RunEvent> events =
                runEventBuffer.replay(runKey, cursor);
            for (com.forgeagent.agent.service.run.RunEventBuffer.RunEvent event : events) {
                writer.write("id: " + event.sequence() + "\n");
                writer.write("event: " + event.name() + "\n");
                writer.write("data: " + oneLine(event.data()) + "\n\n");
                cursor = event.sequence();
            }
            if (!events.isEmpty()) {
                writer.flush();
                lastWrite = System.currentTimeMillis();
            }
            // 心跳用 SSE 注释行,不进事件通道也不影响游标
            if (System.currentTimeMillis() - lastWrite >= STREAM_PING_MS) {
                writer.write(": ping\n\n");
                writer.flush();
                lastWrite = System.currentTimeMillis();
            }
            if (writer.checkError()) {
                return;
            }
            try {
                Thread.sleep(STREAM_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** SSE 的 data 不能含裸换行,压成一行。 */
    private static String oneLine(String data) {
        return data == null ? "" : data.replace("\r", " ").replace("\n", " ");
    }

    /* ---------------- P2 成本与配额 ---------------- */

    /** 会话级成本:token 总量 / 每会话 token / 每次解决 token;未配单价时 amountAvailable=false。 */
    @GetMapping("/assist/cost")
    public R<java.util.Map<String, Object>> cost(@RequestParam(defaultValue = "7") int days) {
        return R.ok(costService.overview(currentWorkspaceId(), days));
    }

    @PostMapping("/assist/cost/rebuild")
    public R<Object> rebuildCost() {
        return R.ok(costService.rebuild(currentWorkspaceId(), new java.util.Date()));
    }

    /** 配额现状:开关、上限、当日已用与处置方式。 */
    @GetMapping("/assist/quota")
    public R<java.util.Map<String, Object>> quota() {
        Long workspaceId = currentWorkspaceId();
        com.forgeagent.customerservice.service.CsQuotaService.Decision decision = quotaService.check(workspaceId);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("enabled", quotaService.enabled());
        data.put("action", quotaService.action());
        data.put("level", decision.level().name());
        data.put("used", decision.used());
        data.put("limit", decision.limit());
        return R.ok(data);
    }

    /* ---------------- P2 收尾:附件与死信 ---------------- */

    /** 会话附件列表(不含缩略图):坐席看客户发了什么。 */
    @GetMapping("/conversations/{cid}/attachments")
    public R<List<java.util.Map<String, Object>>> attachments(@PathVariable Long cid) {
        return R.ok(attachmentService.list(currentWorkspaceId(), cid));
    }

    /** 单个附件详情:带缩略图(图片)或解析摘要(文档)。 */
    @GetMapping("/conversations/{cid}/attachments/{id}")
    public R<com.forgeagent.agentcore.domain.vo.ChatAttachmentVo> attachmentDetail(
        @PathVariable Long cid, @PathVariable Long id) {
        return R.ok(attachmentService.detail(currentWorkspaceId(), cid, id));
    }

    /** 死信列表:默认看 PENDING。 */
    @GetMapping("/dead-letters")
    public R<List<com.forgeagent.customerservice.domain.CsDeadLetter>> deadLetters(
        @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        return R.ok(deadLetterService.list(currentWorkspaceId(), status, limit));
    }

    /** 重投:目前摘要类可自动重投,附件类只能标记不可重投。 */
    @PostMapping("/dead-letters/{id}/replay")
    public R<com.forgeagent.customerservice.domain.CsDeadLetter> replayDeadLetter(@PathVariable Long id) {
        return R.ok(deadLetterService.replay(id, currentWorkspaceId()));
    }

    @PostMapping("/dead-letters/{id}/ignore")
    public R<Boolean> ignoreDeadLetter(@PathVariable Long id) {
        return R.ok(deadLetterService.ignore(id, currentWorkspaceId()));
    }
}
