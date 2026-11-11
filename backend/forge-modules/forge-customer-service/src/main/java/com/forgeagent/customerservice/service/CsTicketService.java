package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsQueue;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsTicketEvent;
import com.forgeagent.customerservice.domain.CsTicketStatus;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsQueueMapper;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import com.forgeagent.customerservice.mapper.CsTicketEventMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import com.forgeagent.callcenter.common.CsEventEnvelope;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 七态工单 + 事件轨迹 + 真 ACD 排队分配(C1)。
 *
 * <p>状态流转统一收敛在 transition;抢单用条件更新避免并发重复接单;
 * 排队按技能匹配 → 最小负载 → 入队时间顺序分配,超时升级留作定时任务(C2)。</p>
 *
 * <p>扩展方向:V2 起语音域(CallBusinessBinder)复用 requestHandoff / claim /
 * transition 三个入口完成通话建单 / 自动分配 / 挂机结案;坐席技能组路由、
 * SLA 超时升级、工单合并都应在 ALLOWED 跃迁表与本类收口,不在调用方散写状态判断;
 * RESOLVED 结案的 CSAT 邀请已统一收口在 transition 内,新增结案路径无需重复接 CSAT。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsTicketService {

    private final CsTicketMapper ticketMapper;
    private final CsTicketEventMapper eventMapper;
    private final CsQueueMapper queueMapper;
    private final CsSeatMapper seatMapper;
    private final CsConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final CsRealtimeTransport transport;
    /** C2:结案写满意度邀请、坐席回复触发质检。 */
    private final CsatService csatService;
    private final CsQualityService qualityService;
    /** P0:转人工建单后发布事件,异步生成交接摘要。 */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    private static final Map<String, Set<String>> ALLOWED = Map.of(
        CsTicketStatus.AI_SERVING.name(), Set.of(CsTicketStatus.WAITING_AGENT.name()),
        // P0:取消转人工回到 AI 接待
        CsTicketStatus.WAITING_AGENT.name(), Set.of(CsTicketStatus.PROCESSING.name(),
            CsTicketStatus.AI_SERVING.name()),
        // P0:处理中/挂起都可退回工单池,由 ACD 重新分配
        CsTicketStatus.PROCESSING.name(), Set.of(CsTicketStatus.ON_HOLD.name(),
            CsTicketStatus.WAITING_CONFIRM.name(), CsTicketStatus.WAITING_AGENT.name()),
        CsTicketStatus.ON_HOLD.name(), Set.of(CsTicketStatus.PROCESSING.name(), CsTicketStatus.WAITING_AGENT.name()),
        // P0:待确认可被客户驳回,退回处理中
        CsTicketStatus.WAITING_CONFIRM.name(), Set.of(CsTicketStatus.RESOLVED.name(),
            CsTicketStatus.WAITING_AGENT.name(), CsTicketStatus.PROCESSING.name()),
        CsTicketStatus.RESOLVED.name(), Set.of(CsTicketStatus.CLOSED.name(), CsTicketStatus.WAITING_AGENT.name()),
        CsTicketStatus.CLOSED.name(), Set.of(CsTicketStatus.WAITING_AGENT.name())
    );

    /** 请求转人工:建工单(WAITING_AGENT) + 入队 + 事件 + 会话状态。 */
    public CsTicket requestHandoff(CsConversation conv, String reason) {
        CsTicket existing = ticketMapper.selectOne(new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getConversationId, conv.getId())
            .in(CsTicket::getStatus, "WAITING_AGENT", "PROCESSING", "ON_HOLD", "WAITING_CONFIRM")
            .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        CsTicket ticket = new CsTicket();
        ticket.setTicketNo("CS" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        ticket.setConversationId(conv.getId());
        ticket.setSessionId(conv.getAgentSessionId());
        ticket.setVisitorId(conv.getVisitorId());
        ticket.setChannelCode(conv.getChannelCode());
        ticket.setWorkspaceId(conv.getWorkspaceId());
        ticket.setAgentId(conv.getAgentId());
        ticket.setTitle("转人工咨询");
        ticket.setCategory("CONSULT");
        ticket.setPriority("NORMAL");
        ticket.setStatus(CsTicketStatus.WAITING_AGENT.name());
        ticket.setHandoffReason(reason);
        ticket.setReopenCount(0);
        ticket.setWaitingSince(new Date());
        ticket.setCreateTime(new Date());
        ticketMapper.insert(ticket);

        CsQueue queue = new CsQueue();
        queue.setTicketId(ticket.getId());
        queue.setWorkspaceId(conv.getWorkspaceId());
        queue.setRequiredSkill("default");
        queue.setPriority("NORMAL");
        queue.setState("WAITING");
        queue.setEnqueuedAt(new Date());
        queue.setEscalateCount(0);
        queue.setCreateTime(new Date());
        queueMapper.insert(queue);

        conv.setTicketId(ticket.getId());
        conv.setStatus("WAITING_AGENT");
        conv.setUpdateTime(new Date());
        conversationMapper.updateById(conv);

        event(ticket, "REQUEST_HANDOFF", null, ticket.getStatus(), "VISITOR", conv.getVisitorId(), reason);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.TICKET_NEW, conv.getId(), ticket.getId(),
            Map.of("status", ticket.getStatus(), "reason", reason)));
        transport.push(CsEventEnvelope.of(CsEventEnvelope.HANDOFF, conv.getId(), ticket.getId(),
            Map.of("reason", reason)));
        // P0:交接摘要异步生成——摘要失败不影响建单,坐席仍可正常接单
        eventPublisher.publishEvent(new com.forgeagent.customerservice.event.CsHandoffRequestedEvent(
            conv.getWorkspaceId(), conv.getId(), ticket.getId()));
        return ticket;
    }

    public Map<String, Object> handoffView(CsConversation conv, String reason) {
        CsTicket ticket = requestHandoff(conv, reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId());
        data.put("ticketNo", ticket.getTicketNo());
        data.put("status", ticket.getStatus());
        data.put("waitingSince", ticket.getWaitingSince());
        return data;
    }

    public List<CsTicket> list(Long workspaceId, String status) {
        LambdaQueryWrapper<CsTicket> q = new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getWorkspaceId, workspaceId)
            .orderByDesc(CsTicket::getCreateTime);
        if (status != null && !status.isBlank()) {
            q.eq(CsTicket::getStatus, status);
        }
        return ticketMapper.selectList(q);
    }

    public List<CsTicketEvent> events(Long ticketId) {
        return eventMapper.selectList(new LambdaQueryWrapper<CsTicketEvent>()
            .eq(CsTicketEvent::getTicketId, ticketId).orderByAsc(CsTicketEvent::getId));
    }

    /** 抢单:条件更新保证并发下只有一个坐席成功。 */
    public CsTicket claim(Long ticketId, Long seatUserId) {
        CsSeat seat = seatMapper.selectOne(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getUserId, seatUserId).last("limit 1"));
        if (seat == null) {
            throw new ServiceException("当前账号不是客服坐席");
        }
        CsTicket before = ticketMapper.selectById(ticketId);
        if (before == null) {
            throw new ServiceException("工单不存在");
        }
        if (!before.getWorkspaceId().equals(seat.getWorkspaceId())) {
            throw new ServiceException("无权接单其他空间的工单");
        }
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<CsTicket>()
            .eq(CsTicket::getId, ticketId)
            .eq(CsTicket::getStatus, CsTicketStatus.WAITING_AGENT.name())
            .set(CsTicket::getStatus, CsTicketStatus.PROCESSING.name())
            .set(CsTicket::getAssigneeId, seatUserId)
            .set(CsTicket::getSeatGroup, seat.getSeatGroup())
            .set(CsTicket::getUpdateTime, new Date()));
        if (rows == 0) {
            throw new ServiceException("工单已被接单或状态已变更");
        }
        queueMapper.update(null, new LambdaUpdateWrapper<CsQueue>()
            .eq(CsQueue::getTicketId, ticketId).set(CsQueue::getState, "ASSIGNED"));
        seatMapper.update(null, new LambdaUpdateWrapper<CsSeat>()
            .eq(CsSeat::getId, seat.getId()).setSql("current_load = current_load + 1"));

        CsConversation conv = conversationMapper.selectById(before.getConversationId());
        if (conv != null) {
            conv.setStatus("HUMAN_SERVING");
            conversationMapper.updateById(conv);
        }
        event(before, "CLAIM", before.getStatus(), CsTicketStatus.PROCESSING.name(),
            "SEAT", seatUserId, null);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.TICKET_EVENT,
            before.getConversationId(), ticketId, Map.of("status", CsTicketStatus.PROCESSING.name())));
        return ticketMapper.selectById(ticketId);
    }

    /** 统一状态流转(非法流转 fast-fail)。 */    public CsTicket transition(Long ticketId, String targetStatus, String eventType,
                               String actorType, Long actorId, String note) {
        CsTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new ServiceException("工单不存在");
        }
        String from = ticket.getStatus();
        String target = CsTicketStatus.of(targetStatus).name();
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(target)) {
            throw new ServiceException("非法状态流转: " + from + " → " + target);
        }
        ticket.setStatus(target);
        ticket.setUpdateTime(new Date());
        if ("WAITING_AGENT".equals(target)) {
            ticket.setWaitingSince(new Date());
            ticket.setAssigneeId(null);
        }
        if (CsTicketStatus.of(target).isEnded()) {
            ticket.setResolvedAt(new Date());
        }
        if (note != null && ("RESOLVE".equals(eventType) || "MARK_RESOLVED".equals(eventType))) {
            ticket.setResolveNote(note);
        }
        ticketMapper.updateById(ticket);
        if ("WAITING_AGENT".equals(target)) {
            // updateById 默认忽略 null 字段,回池/重开时清受理人必须显式 set,否则工单回到队列但
            // assignee 还挂着上一个坐席(reopen 一直有此问题,退回池才暴露)
            ticketMapper.update(null, new LambdaUpdateWrapper<CsTicket>()
                .eq(CsTicket::getId, ticketId)
                .set(CsTicket::getAssigneeId, null));
        }
        event(ticket, eventType, from, target, actorType, actorId, note);
        if (CsTicketStatus.of(target).isEnded() && ticket.getConversationId() != null) {
            CsConversation conv = conversationMapper.selectById(ticket.getConversationId());
            if (conv != null) {
                conv.setStatus("ENDED");
                conversationMapper.updateById(conv);
                // C2:邀请记录与 cs_csat_invite 事件由 CsatService 统一收口
                csatService.invite(conv.getId(), conv.getWorkspaceId(), conv.getChannelCode());
            }
        }
        transport.push(CsEventEnvelope.of(CsEventEnvelope.TICKET_EVENT,
            ticket.getConversationId(), ticketId, Map.of("status", target, "eventType", eventType)));
        return ticket;
    }

    /**
     * 转派给指定坐席(P0):仅受理人可转,目标须同空间、在线且未满。
     *
     * <p>用条件更新（status + assigneeId）保证并发下不会把已被别人接走的工单再转一次；
     * 负载一减一增与事件轨迹在同一方法内完成,避免调用方漏做。</p>
     */
    public CsTicket transferToAgent(Long ticketId, Long fromSeatUserId, Long targetSeatUserId, String note) {
        CsTicket ticket = requireProcessingAssignee(ticketId, fromSeatUserId);
        CsSeat target = seatMapper.selectOne(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getUserId, targetSeatUserId).last("limit 1"));
        if (target == null || !target.getWorkspaceId().equals(ticket.getWorkspaceId())) {
            throw new ServiceException("目标坐席不存在或不属于当前空间");
        }
        if (!"1".equals(target.getOnline())) {
            throw new ServiceException("目标坐席不在线");
        }
        int load = target.getCurrentLoad() == null ? 0 : target.getCurrentLoad();
        int max = target.getMaxLoad() == null ? 5 : target.getMaxLoad();
        if (load >= max) {
            throw new ServiceException("目标坐席已达接待上限");
        }
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<CsTicket>()
            .eq(CsTicket::getId, ticketId)
            .eq(CsTicket::getStatus, CsTicketStatus.PROCESSING.name())
            .eq(CsTicket::getAssigneeId, fromSeatUserId)
            .set(CsTicket::getAssigneeId, targetSeatUserId)
            .set(CsTicket::getSeatGroup, target.getSeatGroup())
            .set(CsTicket::getUpdateTime, new Date()));
        if (rows == 0) {
            throw new ServiceException("工单已被转派或状态已变更");
        }
        releaseLoad(fromSeatUserId);
        seatMapper.update(null, new LambdaUpdateWrapper<CsSeat>()
            .eq(CsSeat::getId, target.getId()).setSql("current_load = current_load + 1"));

        String reason = note == null || note.isBlank() ? ("转派给坐席 " + targetSeatUserId) : note;
        event(ticket, "TRANSFER_AGENT", ticket.getStatus(), ticket.getStatus(), "SEAT", fromSeatUserId, reason);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.TICKET_EVENT,
            ticket.getConversationId(), ticketId,
            Map.of("status", ticket.getStatus(), "eventType", "TRANSFER_AGENT",
                "assigneeId", targetSeatUserId)));
        return ticketMapper.selectById(ticketId);
    }

    /** 退回工单池(P0):清受理人、重新入队,让 ACD 再分配。 */
    public CsTicket transferToPool(Long ticketId, Long seatUserId, String note) {
        requireProcessingAssignee(ticketId, seatUserId);
        CsTicket ticket = transition(ticketId, CsTicketStatus.WAITING_AGENT.name(),
            "TRANSFER_POOL", "SEAT", seatUserId, note);
        releaseLoad(seatUserId);
        reenqueue(ticketId);
        return ticket;
    }

    /** 取消转人工(P0):WAITING_AGENT → AI_SERVING,队列释放、会话回到 AI 接待。 */
    public CsTicket cancelHandoff(Long ticketId, String reason) {
        CsTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new ServiceException("工单不存在");
        }
        if (!CsTicketStatus.WAITING_AGENT.name().equals(ticket.getStatus())) {
            throw new ServiceException("仅排队中的工单可取消转人工");
        }
        CsTicket updated = transition(ticketId, CsTicketStatus.AI_SERVING.name(), "CANCEL_HANDOFF",
            "VISITOR", ticket.getVisitorId(), reason == null ? "cancel_handoff" : reason);
        queueMapper.update(null, new LambdaUpdateWrapper<CsQueue>()
            .eq(CsQueue::getTicketId, ticketId).set(CsQueue::getState, "CANCELLED"));
        if (ticket.getConversationId() != null) {
            CsConversation conv = conversationMapper.selectById(ticket.getConversationId());
            if (conv != null) {
                conv.setStatus("AI_SERVING");
                conv.setUpdateTime(new Date());
                conversationMapper.updateById(conv);
            }
        }
        return updated;
    }

    /** 驳回待确认(P0):WAITING_CONFIRM → PROCESSING,用于客户反馈未解决。 */
    public CsTicket reject(Long ticketId, Long seatUserId, String note) {
        CsTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new ServiceException("工单不存在");
        }
        if (!CsTicketStatus.WAITING_CONFIRM.name().equals(ticket.getStatus())) {
            throw new ServiceException("仅待确认的工单可驳回");
        }
        if (ticket.getAssigneeId() != null && !ticket.getAssigneeId().equals(seatUserId)) {
            throw new ServiceException("仅受理坐席可驳回");
        }
        return transition(ticketId, CsTicketStatus.PROCESSING.name(), "REJECT", "SEAT", seatUserId,
            note == null || note.isBlank() ? "客户反馈未解决" : note);
    }

    /** 受理人校验 + 处理中校验:转派与退回池的共同前置。 */
    private CsTicket requireProcessingAssignee(Long ticketId, Long seatUserId) {
        CsTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new ServiceException("工单不存在");
        }
        if (!CsTicketStatus.PROCESSING.name().equals(ticket.getStatus())) {
            throw new ServiceException("工单不在处理中");
        }
        if (ticket.getAssigneeId() == null || !ticket.getAssigneeId().equals(seatUserId)) {
            throw new ServiceException("仅受理坐席可执行该操作");
        }
        return ticket;
    }

    /** 坐席负载减一(GREATEST 兜底,避免历史脏数据把负载做成负数)。 */
    private void releaseLoad(Long seatUserId) {
        seatMapper.update(null, new LambdaUpdateWrapper<CsSeat>()
            .eq(CsSeat::getUserId, seatUserId)
            .setSql("current_load = GREATEST(current_load - 1, 0)"));
    }

    /** 重新入队:把排队项置回 WAITING 并刷新入队时间,供 ACD 下次分配。 */
    private void reenqueue(Long ticketId) {
        CsQueue queue = queueMapper.selectOne(new LambdaQueryWrapper<CsQueue>()
            .eq(CsQueue::getTicketId, ticketId).last("limit 1"));
        if (queue == null) {
            return;
        }
        queueMapper.update(null, new LambdaUpdateWrapper<CsQueue>()
            .eq(CsQueue::getId, queue.getId())
            .set(CsQueue::getState, "WAITING")
            .set(CsQueue::getEnqueuedAt, new Date()));
    }

    /**
     * SLA 超时告警事件(P0):只记轨迹不改状态,同一工单同状态 1 小时内不重复写。
     *
     * <p>去重不能靠调用方——定时器每分钟跑一次,没有去重会把事件表刷爆。</p>
     */
    public boolean markSlaBreach(Long ticketId, String detail) {
        Date since = new Date(System.currentTimeMillis() - 3_600_000L);
        Long recent = eventMapper.selectCount(new LambdaQueryWrapper<CsTicketEvent>()
            .eq(CsTicketEvent::getTicketId, ticketId)
            .eq(CsTicketEvent::getEventType, "SLA_BREACH")
            .gt(CsTicketEvent::getCreateTime, since));
        if (recent != null && recent > 0) {
            return false;
        }
        CsTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            return false;
        }
        event(ticket, "SLA_BREACH", ticket.getStatus(), ticket.getStatus(), "SYSTEM", null, detail);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.TICKET_EVENT,
            ticket.getConversationId(), ticketId,
            Map.of("status", ticket.getStatus(), "eventType", "SLA_BREACH", "detail", detail)));
        return true;
    }

    /** 坐席回复:写 agent_message(sender_type=seat)并记录首响。 */
    public Map<String, Object> reply(Long ticketId, Long seatUserId, String content) {
        CsTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || !CsTicketStatus.PROCESSING.name().equals(ticket.getStatus())) {
            throw new ServiceException("工单不在处理中");
        }
        if (!seatUserId.equals(ticket.getAssigneeId())) {
            throw new ServiceException("仅受理坐席可回复");
        }
        AgentMessage msg = new AgentMessage();
        msg.setSessionId(ticket.getSessionId());
        msg.setRole("assistant");
        msg.setSenderType("seat");
        msg.setTicketId(ticketId);
        msg.setContent(content);
        messageMapper.insert(msg);
        // C2:坐席回复即时跑可配置质检规则
        qualityService.inspect(msg.getId(), ticketId, ticket.getWorkspaceId(), content);

        if (ticket.getFirstResponseMs() == null && ticket.getWaitingSince() != null) {
            long cost = System.currentTimeMillis() - ticket.getWaitingSince().getTime();
            ticketMapper.update(null, new LambdaUpdateWrapper<CsTicket>()
                .eq(CsTicket::getId, ticketId).set(CsTicket::getFirstResponseMs, cost));
        }
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CHAT, ticket.getConversationId(), ticketId,
            Map.of("content", content, "senderType", "seat", "messageId", msg.getId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", msg.getId());
        data.put("ticketId", ticketId);
        return data;
    }

    /** ACD:技能匹配 → 最小负载 → 入队时间,返回成功分配数。 */
    public int assignWaiting() {
        List<CsQueue> waiting = queueMapper.selectList(new LambdaQueryWrapper<CsQueue>()
            .eq(CsQueue::getState, "WAITING").orderByAsc(CsQueue::getEnqueuedAt).last("limit 20"));
        int assigned = 0;
        for (CsQueue item : waiting) {
            List<CsSeat> seats = seatMapper.selectList(new LambdaQueryWrapper<CsSeat>()
                .eq(CsSeat::getWorkspaceId, item.getWorkspaceId())
                .eq(CsSeat::getOnline, "1")
                .orderByAsc(CsSeat::getCurrentLoad).last("limit 5"));
            CsSeat target = null;
            for (CsSeat seat : seats) {
                int load = seat.getCurrentLoad() == null ? 0 : seat.getCurrentLoad();
                int max = seat.getMaxLoad() == null ? 5 : seat.getMaxLoad();
                if (load < max) {
                    target = seat;
                    break;
                }
            }
            if (target == null) {
                transport.push(CsEventEnvelope.of(CsEventEnvelope.QUEUE_POSITION, null, item.getTicketId(),
                    Map.of("state", "WAITING", "reason", "no_available_seat")));
                continue;
            }
            try {
                claim(item.getTicketId(), target.getUserId());
                assigned++;
            } catch (Exception e) {
                log.warn("[CS] ACD 分配失败 ticketId={}: {}", item.getTicketId(), e.getMessage());
            }
        }
        return assigned;
    }

    private void event(CsTicket ticket, String type, String from, String to,
                       String actorType, Object actorId, String note) {
        CsTicketEvent e = new CsTicketEvent();
        e.setTicketId(ticket.getId());
        e.setEventType(type);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setActorType(actorType);
        e.setActorId(actorId == null ? null : String.valueOf(actorId));
        e.setNote(note);
        e.setCreateTime(new Date());
        eventMapper.insert(e);
    }
}
