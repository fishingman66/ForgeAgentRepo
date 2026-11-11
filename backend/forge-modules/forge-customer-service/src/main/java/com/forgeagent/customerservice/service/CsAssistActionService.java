package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.compliance.CsComplianceService;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsSeatSuggestion;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsSeatSuggestionMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 坐席辅助动作执行(A4):把「推荐卡片上的按钮」翻译成一次既有写操作。
 *
 * <p>三条纪律:</p>
 * <ol>
 *   <li><b>只执行服务端生成过的动作</b>——按 code 在推荐记录的 actions 快照里查,
 *       查不到即拒绝,前端无法通过传参构造新动作;</li>
 *   <li><b>鉴权沿用既有端点</b>——REPLY/LINK 由 {@code reply} 校验受理坐席,
 *       RESOLVE 在这里补同样的受理人校验,不因为「是辅助功能」放宽;</li>
 *   <li><b>执行留痕</b>——adopt_action 追加 {@code action:CODE}(幂等键),
 *       同时写 SEAT_ACTION 审计,便于事后追责与 A5 采纳率统计。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsAssistActionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADOPT_PREFIX = "action:";

    private final CsSeatSuggestionMapper suggestionMapper;
    private final CsConversationMapper conversationMapper;
    private final CsTicketMapper ticketMapper;
    private final CsTicketService ticketService;
    private final CsComplianceService complianceService;

    /** 执行一条动作;重复执行同一动作直接返回已执行,不重复发消息/流转。 */
    public Map<String, Object> execute(Long suggestionId, String code, Long workspaceId, Long seatUserId) {
        if (!SeatAssistActions.isAllowed(code)) {
            throw new ServiceException("不支持的动作类型");
        }
        String actionCode = code.toUpperCase();
        CsSeatSuggestion suggestion = suggestionMapper.selectById(suggestionId);
        if (suggestion == null) {
            throw new ServiceException("推荐记录不存在");
        }
        CsConversation conv = conversationMapper.selectById(suggestion.getConversationId());
        if (conv == null || !conv.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("无权访问该推荐");
        }

        String token = ADOPT_PREFIX + actionCode;
        String executed = suggestion.getAdoptAction() == null ? "" : suggestion.getAdoptAction();
        if (executed.contains(token)) {
            return result(suggestionId, actionCode, null, "该动作已执行过");
        }

        Map<String, Object> action = SeatAssistActions.find(
            parseActions(suggestion.getActions()), actionCode);
        if (action == null) {
            throw new ServiceException("该推荐未包含此动作");
        }

        Long ticketId = suggestion.getTicketId() != null ? suggestion.getTicketId() : conv.getTicketId();
        if (ticketId == null) {
            throw new ServiceException("会话尚未建立工单,无法执行该动作");
        }
        CsTicket ticket = requireAssignee(ticketId, workspaceId, seatUserId);

        Map<String, Object> detail;
        switch (actionCode) {
            case SeatAssistActions.REPLY, SeatAssistActions.LINK -> {
                String content = SeatAssistActions.contentOf(action);
                if (content == null || content.isBlank()) {
                    throw new ServiceException("动作缺少可发送内容");
                }
                detail = ticketService.reply(ticketId, seatUserId, content);
            }
            case SeatAssistActions.RESOLVE -> {
                ticketService.transition(ticketId, "WAITING_CONFIRM", "MARK_RESOLVED",
                    "SEAT", seatUserId, "坐席辅助一键标记解决");
                detail = Map.of("ticketId", ticketId, "status", "WAITING_CONFIRM");
            }
            default -> throw new ServiceException("不支持的动作类型");
        }

        markExecuted(suggestion, executed, token);
        complianceService.record("SEAT_ACTION", "SEAT", String.valueOf(seatUserId),
            workspaceId, conv.getChannelCode(),
            "suggestion=" + suggestionId + " action=" + actionCode + " ticket=" + ticket.getTicketNo());
        log.info("[CS] 坐席动作已执行 suggestionId={} action={} ticketId={} seatUserId={}",
            suggestionId, actionCode, ticketId, seatUserId);
        return result(suggestionId, actionCode, detail, "已执行");
    }

    /** 受理人校验:与 reply 端点同口径,避免授权动作绕过工作台的权限判断。 */
    private CsTicket requireAssignee(Long ticketId, Long workspaceId, Long seatUserId) {
        CsTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new ServiceException("工单不存在");
        }
        if (!ticket.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("无权操作其他空间的工单");
        }
        if (!"PROCESSING".equals(ticket.getStatus())) {
            throw new ServiceException("工单不在处理中");
        }
        if (ticket.getAssigneeId() == null || !ticket.getAssigneeId().equals(seatUserId)) {
            throw new ServiceException("仅受理坐席可执行该动作");
        }
        return ticket;
    }

    /** 埋点:动作追加到 adopt_action(逗号分隔),同时标记为已采纳。 */
    private void markExecuted(CsSeatSuggestion suggestion, String executed, String token) {
        String next = executed.isBlank() ? token : executed + "," + token;
        suggestionMapper.update(null, new LambdaUpdateWrapper<CsSeatSuggestion>()
            .eq(CsSeatSuggestion::getId, suggestion.getId())
            .set(CsSeatSuggestion::getAdopted, "1")
            .set(CsSeatSuggestion::getAdoptAction, next));
    }

    private List<Map<String, Object>> parseActions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("[CS] 推荐动作快照解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> result(Long suggestionId, String code,
                                       Map<String, Object> detail, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("suggestionId", suggestionId);
        data.put("code", code);
        data.put("executed", true);
        data.put("message", message);
        if (detail != null) {
            data.put("detail", detail);
        }
        return data;
    }
}
