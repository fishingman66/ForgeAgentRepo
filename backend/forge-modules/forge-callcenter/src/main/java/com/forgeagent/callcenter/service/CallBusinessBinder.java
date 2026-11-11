package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.service.CsTicketService;
import com.forgeagent.customerservice.service.CsVisitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 通话业务绑定(V2,方案 v1 第八章):callId ↔ conversationId ↔ ticketId 唯一写入口。
 *
 * <p>ring 建会话(复用 CsVisitorService.createSession,场景 scene=cs、渠道 voice_inbound)、
 * transfer 建工单并入 ACD 队列(CsTicketService.requestHandoff)、坐席就绪触发 ACD 自动分配、
 * 挂机按合法跃迁表推进结案(RESOLVED 时 CsatService 自动发 CSAT 邀请),
 * 再串起转写质检与通话指标。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallBusinessBinder {

    private final CsChannelMapper channelMapper;
    private final CsConversationMapper conversationMapper;
    private final CsVisitorService visitorService;
    private final CsTicketService ticketService;
    private final CallTranscriptQualityService transcriptQuality;
    private final CallMetricService metricService;
    private final CsCallMapper callMapper;

    /** ring:通话绑定客服会话;渠道缺失或建会话失败只记日志,不阻断回调。 */
    public void onRing(CsCall call, CsVoiceEvent event) {
        if (call.getConversationId() != null) {
            return;
        }
        CsChannel channel = channelMapper.selectOne(new LambdaQueryWrapper<CsChannel>()
            .eq(CsChannel::getChannelCode, call.getChannelCode()).last("limit 1"));
        if (channel == null) {
            log.warn("[CC] 通话 {} 的文本渠道 {} 不存在,跳过会话绑定", call.getCallId(), call.getChannelCode());
            return;
        }
        try {
            String external = call.getCallerNumber() == null || call.getCallerNumber().isBlank()
                ? call.getCallId() : call.getCallerNumber();
            Map<String, Object> session = visitorService.createSession(channel, external);
            Object conversationId = session.get("conversationId");
            if (conversationId instanceof Long cid) {
                call.setConversationId(cid);
                callMapper.updateById(call);
                log.info("[CC] 通话 {} 已绑定会话 {}", call.getCallId(), cid);
            }
        } catch (Exception e) {
            log.error("[CC] 通话建会话失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
    }

    /** transfer:转人工 → 建工单(WAITING_AGENT)并入 ACD 队列,工单号回填通话。 */
    public void onTransfer(CsCall call, CsVoiceEvent event) {
        CsConversation conv = conversationOf(call);
        if (conv == null) {
            return;
        }
        try {
            CsTicket ticket = ticketService.requestHandoff(conv,
                event.str("reason") == null ? "voice_transfer" : event.str("reason"));
            call.setTicketId(ticket.getId());
            callMapper.updateById(call);
        } catch (Exception e) {
            log.error("[CC] 转人工建工单失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
    }

    /** seatState READY:坐席就绪即触发一次 ACD 自动分配(在线坐席才成为目标)。 */
    public void onSeatState(CsCall call, CsVoiceEvent event) {
        if ("READY".equalsIgnoreCase(event.str("readyState"))
            && call.getTicketId() != null && call.getConversationId() != null) {
            int assigned = ticketService.assignWaiting();
            log.info("[CC] 坐席就绪触发 ACD 分配 callId={} assigned={}", call.getCallId(), assigned);
        }
    }

    /** 挂机:合法推进结案(RESOLVED 自动发 CSAT)→ 转写质检 → 通话指标。 */
    public void onHangup(CsCall call) {
        try {
            closeTicket(call);
        } catch (Exception e) {
            log.error("[CC] 挂机结案失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
        try {
            transcriptQuality.inspectCall(call);
        } catch (Exception e) {
            log.error("[CC] 转写质检失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
        try {
            metricService.rebuild(call.getWorkspaceId(), new java.util.Date());
        } catch (Exception e) {
            log.error("[CC] 通话指标失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
    }

    private void closeTicket(CsCall call) {
        if (call.getTicketId() == null) {
            return;
        }
        CsTicket ticket = ticketService.list(call.getWorkspaceId(), null).stream()
            .filter(t -> t.getId().equals(call.getTicketId())).findFirst().orElse(null);
        if (ticket == null) {
            return;
        }
        for (String step : closeSteps(ticket.getStatus())) {
            ticketService.transition(ticket.getId(), step, "CALL_HANGUP", "SYSTEM", null,
                "通话挂机自动结案 callId=" + call.getCallId());
        }
    }

    /**
     * 挂机结案的合法推进序列(纯函数,单测锁定):与 C1 跃迁表一致,
     * 不引入新的非法流转。WAITING_AGENT 保留排队由 ACD 继续,避免无坐席场景硬造终态。
     */
    public static List<String> closeSteps(String ticketStatus) {
        return switch (ticketStatus == null ? "" : ticketStatus) {
            case "PROCESSING" -> List.of("WAITING_CONFIRM", "RESOLVED");
            case "WAITING_CONFIRM" -> List.of("RESOLVED");
            default -> List.of();
        };
    }

    private CsConversation conversationOf(CsCall call) {
        if (call.getConversationId() == null) {
            log.warn("[CC] 通话 {} 尚未绑定会话,跳过转人工", call.getCallId());
            return null;
        }
        return conversationMapper.selectById(call.getConversationId());
    }
}
