package com.forgeagent.customerservice.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import com.forgeagent.customerservice.service.CsTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 坐席工作台与渠道管理(C1),登录态由 Sa-Token 保证,空间取坐席所属 workspace。
 */
@SaCheckLogin
@ConditionalOnCustomerServiceEnabled
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/customerservice")
public class CsSeatController extends BaseController {

    private final CsTicketService ticketService;
    private final CsSeatMapper seatMapper;
    private final CsChannelMapper channelMapper;
    /** C4:渠道密钥落库前加密,列表不回显密文。 */
    private final com.forgeagent.customerservice.compliance.CsSecretCipher secretCipher;

    @GetMapping("/tickets")
    public R<List<CsTicket>> tickets(@RequestParam(required = false) String status) {
        return R.ok(ticketService.list(currentWorkspaceId(), status));
    }

    @GetMapping("/tickets/{id}")
    public R<Map<String, Object>> ticket(@PathVariable Long id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticket", ticketDetail(id));
        data.put("events", ticketService.events(id));
        return R.ok(data);
    }

    @PostMapping("/tickets/{id}/claim")
    public R<CsTicket> claim(@PathVariable Long id) {
        return R.ok(ticketService.claim(id, LoginHelper.getUserId()));
    }

    @PostMapping("/tickets/{id}/reply")
    public R<Map<String, Object>> reply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        return R.ok(ticketService.reply(id, LoginHelper.getUserId(), content));
    }

    @PostMapping("/tickets/{id}/hold")
    public R<CsTicket> hold(@PathVariable Long id) {
        return R.ok(ticketService.transition(id, "ON_HOLD", "HOLD", "SEAT", LoginHelper.getUserId(), null));
    }

    @PostMapping("/tickets/{id}/resume")
    public R<CsTicket> resume(@PathVariable Long id) {
        return R.ok(ticketService.transition(id, "PROCESSING", "RESUME", "SEAT", LoginHelper.getUserId(), null));
    }

    @PostMapping("/tickets/{id}/resolve")
    public R<CsTicket> resolve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        return R.ok(ticketService.transition(id, "WAITING_CONFIRM", "MARK_RESOLVED",
            "SEAT", LoginHelper.getUserId(), note));
    }

    @PostMapping("/tickets/{id}/close")
    public R<CsTicket> close(@PathVariable Long id) {
        return R.ok(ticketService.transition(id, "CLOSED", "CLOSE", "SEAT", LoginHelper.getUserId(), null));
    }

    @PostMapping("/tickets/{id}/reopen")
    public R<CsTicket> reopen(@PathVariable Long id) {
        return R.ok(ticketService.transition(id, "WAITING_AGENT", "REOPEN", "SEAT", LoginHelper.getUserId(), null));
    }

    /**
     * 转派(P0):body 带 assigneeId 转给指定坐席,不带则退回工单池。
     * 目标坐席在线且未满、当前操作人是受理人,两个条件由服务层校验。
     */
    @PostMapping("/tickets/{id}/transfer")
    public R<CsTicket> transfer(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Object target = body == null ? null : body.get("assigneeId");
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        if (target == null || String.valueOf(target).isBlank()) {
            return R.ok(ticketService.transferToPool(id, LoginHelper.getUserId(), note));
        }
        long assigneeId;
        try {
            assigneeId = Long.parseLong(String.valueOf(target));
        } catch (NumberFormatException e) {
            return R.fail("目标坐席ID非法");
        }
        return R.ok(ticketService.transferToAgent(id, LoginHelper.getUserId(), assigneeId, note));
    }

    /** 取消转人工(P0):排队中的工单回到 AI 接待。 */
    @PostMapping("/tickets/{id}/cancel-handoff")
    public R<CsTicket> cancelHandoff(@PathVariable Long id,
                                     @RequestParam(required = false) String reason) {
        return R.ok(ticketService.cancelHandoff(id, reason));
    }

    /** 驳回待确认(P0):客户反馈未解决,退回处理中。 */
    @PostMapping("/tickets/{id}/reject")
    public R<CsTicket> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        return R.ok(ticketService.reject(id, LoginHelper.getUserId(), note));
    }

    @PostMapping("/queue/assign")
    public R<Integer> assign() {
        return R.ok(ticketService.assignWaiting());
    }

    @GetMapping("/channels")
    public R<List<CsChannel>> channels() {
        List<CsChannel> list = channelMapper.selectList(new LambdaQueryWrapper<CsChannel>()
            .eq(CsChannel::getWorkspaceId, currentWorkspaceId()).orderByDesc(CsChannel::getId));
        // C4:密钥不回显到管理端
        list.forEach(item -> item.setChannelSecret(null));
        return R.ok(list);
    }

    @PostMapping("/channels")
    public R<CsChannel> saveChannel(@RequestBody CsChannel channel) {
        channel.setWorkspaceId(currentWorkspaceId());
        channel.setStatus(channel.getStatus() == null ? "0" : channel.getStatus());
        channel.setCreateTime(new Date());
        String secret = channel.getChannelSecret();
        if (secret == null || secret.isBlank()) {
            // 编辑时留空表示不改动既有密钥(MyBatis-Plus 默认不更新 null 字段)
            channel.setChannelSecret(null);
        } else if (secretCipher != null) {
            channel.setChannelSecret(secretCipher.encrypt(secret));
        }
        if (channel.getId() == null) {
            channelMapper.insert(channel);
        } else {
            channelMapper.updateById(channel);
        }
        channel.setChannelSecret(null);
        return R.ok(channel);
    }

    @GetMapping("/seats")
    public R<List<CsSeat>> seats() {
        return R.ok(seatMapper.selectList(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getWorkspaceId, currentWorkspaceId())));
    }

    private CsTicket ticketDetail(Long id) {
        for (CsTicket t : ticketService.list(currentWorkspaceId(), null)) {
            if (t.getId().equals(id)) {
                return t;
            }
        }
        return null;
    }

    private Long currentWorkspaceId() {
        CsSeat seat = seatMapper.selectOne(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getUserId, LoginHelper.getUserId()).last("limit 1"));
        return seat == null ? -1L : seat.getWorkspaceId();
    }
}
