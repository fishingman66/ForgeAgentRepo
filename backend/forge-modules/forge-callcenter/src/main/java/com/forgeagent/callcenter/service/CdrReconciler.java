package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 话单对账(V1):cdr 事件补写时长与挂机时间;CDR 迟到对 ENDED 通话照常补写,
 * 状态机保证不回退,指标在 V2 按 (workspace, day) 聚合。
 */
@Slf4j
@Service
@ConditionalOnCallcenterEnabled
public class CdrReconciler {

    private static final DateTimeFormatter DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void reconcile(CsCall call, CsVoiceEvent event) {
        Long duration = event.longValue("durationSec");
        Long billSec = event.longValue("billSec");
        if (duration != null) {
            call.setDurationSec(duration.intValue());
        }
        if (billSec != null) {
            call.setBillSec(billSec.intValue());
        }
        String hangupAt = event.str("hangupAt");
        if (hangupAt != null) {
            call.setHangupAt(parse(hangupAt));
        }
    }

    public void applyHangup(CsCall call, CsVoiceEvent event, Date now) {
        call.setHangupAt(now);
        call.setHangupCause(event.str("hangupCause"));
        call.setHangupBy(event.str("hangupBy"));
        Long sipCode = event.longValue("sipCode");
        call.setSipCode(sipCode == null ? null : sipCode.intValue());
    }

    private Date parse(String value) {
        try {
            return Date.from(LocalDateTime.parse(value, DATE_TIME).atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            log.warn("[CC] CDR hangupAt 解析失败: {}", value);
            return null;
        }
    }
}
