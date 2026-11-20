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
import org.springframework.validation.annotation.Validated;
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
    public R<List<com.forgeagent.customerservice.domain.vo.CsTicketVo>> tickets(@RequestParam(required = false) String status) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.list(currentWorkspaceId(), status), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    @GetMapping("/tickets/{id}")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketDetailVo> ticket(@PathVariable Long id) {
        com.forgeagent.customerservice.domain.vo.CsTicketDetailVo data = new com.forgeagent.customerservice.domain.vo.CsTicketDetailVo();
        data.setTicket(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketDetail(id), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
        data.setEvents(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.events(id), com.forgeagent.customerservice.domain.vo.CsTicketEventVo.class));
        return R.ok(data);
    }

    @PostMapping("/tickets/{id}/claim")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> claim(@PathVariable Long id) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.claim(id, LoginHelper.getUserId()), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    @PostMapping("/tickets/{id}/reply")
    public R<com.forgeagent.customerservice.domain.vo.CsReplyResultVo> reply(@PathVariable Long id, @Validated @RequestBody com.forgeagent.customerservice.domain.bo.CsReplyBo bo) {
        return R.ok(ticketService.reply(id, LoginHelper.getUserId(), bo.getContent()));
    }

    /** A3 内部备注:仅坐席可见的 NOTE 事件,不进访客消息通道。 */
    @PostMapping("/tickets/{id}/note")
    public R<Boolean> note(@PathVariable Long id, @Validated @RequestBody com.forgeagent.customerservice.domain.bo.CsNoteBo bo) {
        ticketService.addNote(id, LoginHelper.getUserId(), bo.getNote());
        return R.ok(true);
    }

    /**
     * A3:当前坐席收到的 @提及增量(游标 sinceId,首次传 0)。
     * 坐席端定时拉取并弹提醒;空间过滤在 SQL 里做,不跨空间泄漏。
     */
    @GetMapping("/mentions")
    public R<List<com.forgeagent.customerservice.domain.vo.CsTicketEventVo>> mentions(
        @RequestParam(required = false) Long sinceId) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(
            ticketService.mentions(currentWorkspaceId(), LoginHelper.getUserId(), sinceId),
            com.forgeagent.customerservice.domain.vo.CsTicketEventVo.class));
    }

    @PostMapping("/tickets/{id}/hold")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> hold(@PathVariable Long id) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.transition(id, "ON_HOLD", "HOLD", "SEAT", LoginHelper.getUserId(), null), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    @PostMapping("/tickets/{id}/resume")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> resume(@PathVariable Long id) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.transition(id, "PROCESSING", "RESUME", "SEAT", LoginHelper.getUserId(), null), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    @PostMapping("/tickets/{id}/resolve")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> resolve(@PathVariable Long id, @RequestBody(required = false) com.forgeagent.customerservice.domain.bo.CsNoteBo bo) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.transition(id, "WAITING_CONFIRM", "MARK_RESOLVED",
            "SEAT", LoginHelper.getUserId(), bo == null ? null : bo.getNote()), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    @PostMapping("/tickets/{id}/close")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> close(@PathVariable Long id) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.transition(id, "CLOSED", "CLOSE", "SEAT", LoginHelper.getUserId(), null), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    @PostMapping("/tickets/{id}/reopen")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> reopen(@PathVariable Long id) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.transition(id, "WAITING_AGENT", "REOPEN", "SEAT", LoginHelper.getUserId(), null), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    /**
     * 转派(P0):body 带 assigneeId 转给指定坐席,不带则退回工单池。
     * 目标坐席在线且未满、当前操作人是受理人,两个条件由服务层校验。
     */
    @PostMapping("/tickets/{id}/transfer")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> transfer(@PathVariable Long id, @RequestBody(required = false) com.forgeagent.customerservice.domain.bo.CsTransferBo bo) {
        Long target = bo == null ? null : bo.getAssigneeId();
        String note = bo == null ? null : bo.getNote();
        if (target == null) {
            return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.transferToPool(id, LoginHelper.getUserId(), note), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
        }
        long assigneeId;
        try {
            assigneeId = target;
        } catch (NumberFormatException e) {
            return R.fail("目标坐席ID非法");
        }
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.transferToAgent(id, LoginHelper.getUserId(), assigneeId, note), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    /** 取消转人工(P0):排队中的工单回到 AI 接待。 */
    @PostMapping("/tickets/{id}/cancel-handoff")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> cancelHandoff(@PathVariable Long id,
                                     @RequestParam(required = false) String reason) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.cancelHandoff(id, reason), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    /** 驳回待确认(P0):客户反馈未解决,退回处理中。 */
    @PostMapping("/tickets/{id}/reject")
    public R<com.forgeagent.customerservice.domain.vo.CsTicketVo> reject(@PathVariable Long id, @RequestBody(required = false) com.forgeagent.customerservice.domain.bo.CsNoteBo bo) {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(ticketService.reject(id, LoginHelper.getUserId(), bo == null ? null : bo.getNote()), com.forgeagent.customerservice.domain.vo.CsTicketVo.class));
    }

    @PostMapping("/queue/assign")
    public R<Integer> assign() {
        return R.ok(ticketService.assignWaiting());
    }

    @GetMapping("/channels")
    public R<List<com.forgeagent.customerservice.domain.vo.CsChannelVo>> channels() {
        List<CsChannel> list = channelMapper.selectList(new LambdaQueryWrapper<CsChannel>()
            .eq(CsChannel::getWorkspaceId, currentWorkspaceId()).orderByDesc(CsChannel::getId));
        // C4:密钥不回显到管理端
        list.forEach(item -> item.setChannelSecret(null));
        List<com.forgeagent.customerservice.domain.vo.CsChannelVo> vos =
            com.forgeagent.common.core.utils.MapstructUtils.convert(list, com.forgeagent.customerservice.domain.vo.CsChannelVo.class);
        if (vos != null) {
            for (int i = 0; i < vos.size(); i++) {
                // B5:EncodingAESKey 与密钥同级别,只回"是否已启用加密模式"
                boolean encrypted = list.get(i).getEncodingAesKey() != null
                    && !list.get(i).getEncodingAesKey().isBlank();
                vos.get(i).setWecomEncrypted(encrypted);
                boolean mailConfigured = list.get(i).getChannelConfig() != null
                    && !list.get(i).getChannelConfig().isBlank();
                vos.get(i).setChannelConfigured(mailConfigured);
            }
        }
        return R.ok(vos);
    }

    @PostMapping("/channels")
    public R<com.forgeagent.customerservice.domain.vo.CsChannelVo> saveChannel(@Validated @RequestBody com.forgeagent.customerservice.domain.bo.CsChannelSaveBo bo) {
        CsChannel channel = com.forgeagent.common.core.utils.MapstructUtils.convert(bo, CsChannel.class);
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
        // B5:邮件渠道配置(含 IMAP/SMTP 密码)整段加密;留空表示不改动既有值
        String mailConfig = channel.getChannelConfig();
        if (mailConfig == null || mailConfig.isBlank()) {
            channel.setChannelConfig(null);
        } else if (secretCipher != null) {
            channel.setChannelConfig(secretCipher.encrypt(mailConfig));
        }
        // B5:EncodingAESKey 与 channelSecret 同级别加密;留空表示不改动既有值
        String aesKey = channel.getEncodingAesKey();
        if (aesKey == null || aesKey.isBlank()) {
            channel.setEncodingAesKey(null);
        } else if (secretCipher != null) {
            channel.setEncodingAesKey(secretCipher.encrypt(aesKey));
        }
        if (channel.getId() == null) {
            channelMapper.insert(channel);
        } else {
            channelMapper.updateById(channel);
        }
        channel.setChannelSecret(null);
        com.forgeagent.customerservice.domain.vo.CsChannelVo saved =
            com.forgeagent.common.core.utils.MapstructUtils.convert(channel, com.forgeagent.customerservice.domain.vo.CsChannelVo.class);
        if (saved != null) {
            saved.setWecomEncrypted(aesKey != null && !aesKey.isBlank());
            saved.setChannelConfigured(mailConfig != null && !mailConfig.isBlank());
            // 实体上已改成密文,别让前端拿到
            saved.setCorpId(channel.getCorpId());
        }
        return R.ok(saved);
    }

    @GetMapping("/seats")
    public R<List<com.forgeagent.customerservice.domain.vo.CsSeatVo>> seats() {
        return R.ok(com.forgeagent.common.core.utils.MapstructUtils.convert(seatMapper.selectList(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getWorkspaceId, currentWorkspaceId())), com.forgeagent.customerservice.domain.vo.CsSeatVo.class));
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
