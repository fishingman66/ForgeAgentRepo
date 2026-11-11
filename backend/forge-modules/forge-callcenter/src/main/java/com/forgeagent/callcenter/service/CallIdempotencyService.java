package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallEvent;
import com.forgeagent.callcenter.domain.CsCallStatus;
import com.forgeagent.callcenter.mapper.CsCallEventMapper;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 呼叫与事件幂等(V1)。
 *
 * <p>两层防线:呼叫级靠 {@code agent_cc_call} 唯一键 (media_provider, call_id),
 * 事件级靠 {@code agent_cc_call_event} 唯一键 (call_id, event_seq, event_type);
 * 事件指纹(V0 契约)用于重复检测日志与排障,不单独入库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallIdempotencyService {

    private final CsCallMapper callMapper;
    private final CsCallEventMapper eventMapper;

    /** 按 (provider, callId) 查通话;并发首呼由唯一键兜底。 */
    public CsCall findCall(String provider, String callId) {
        return callMapper.selectOne(new LambdaQueryWrapper<CsCall>()
            .eq(CsCall::getMediaProvider, provider)
            .eq(CsCall::getCallId, callId)
            .last("limit 1"));
    }

    /**
     * 事件轨迹落库,重复事件返回 false(直接忽略,响应仍为成功)。
     */
    public boolean tryRecord(CsCall call, CsVoiceEventView view, String fromStatus, String toStatus) {
        CsCallEvent record = new CsCallEvent();
        record.setCallId(call.getId());
        record.setEventSeq(view.eventGenTime());
        record.setEventType(view.eventType());
        record.setFromStatus(fromStatus);
        record.setToStatus(CsCallStatus.of(toStatus) == null ? null
            : CsCallStatus.advance(fromStatus, toStatus));
        record.setActorType("PROVIDER");
        record.setPayload(view.payload());
        record.setCreateTime(new Date());
        try {
            eventMapper.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("[CC] 重复事件忽略 callId={} seq={} type={}",
                call.getCallId(), view.eventGenTime(), view.eventType());
            return false;
        }
    }

    /** 调度层的最小事件视图,避免 service 之间互相依赖 record 结构。 */
    public record CsVoiceEventView(long eventGenTime, String eventType, String payload) {
    }
}
