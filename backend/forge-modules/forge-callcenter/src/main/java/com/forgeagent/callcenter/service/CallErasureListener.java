package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.mapper.CsCallEventMapper;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.forgeagent.callcenter.storage.RecordingStorage;
import com.forgeagent.customerservice.event.CsConversationErasedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话擦除级联(V2):监听 C4 的擦除事件,同步清理语音域数据——
 * 通话记录、事件轨迹、转写分片与录音对象,并追加 RECORDING_PURGE 审计。
 * 事件机制保证 customerservice 不反向依赖 callcenter。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallErasureListener {

    private final CsCallMapper callMapper;
    private final CsCallEventMapper eventMapper;
    private final CsCallSegmentMapper segmentMapper;
    private final RecordingStorage storage;
    private final com.forgeagent.customerservice.compliance.CsComplianceService complianceService;

    @EventListener
    public void onConversationErased(CsConversationErasedEvent event) {
        List<CsCall> calls = callMapper.selectList(new LambdaQueryWrapper<CsCall>()
            .eq(CsCall::getConversationId, event.conversationId()));
        for (CsCall call : calls) {
            eventMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.forgeagent.callcenter.domain.CsCallEvent>()
                .eq(com.forgeagent.callcenter.domain.CsCallEvent::getCallId, call.getId()));
            segmentMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.forgeagent.callcenter.domain.CsCallSegment>()
                .eq(com.forgeagent.callcenter.domain.CsCallSegment::getCallId, call.getId()));
            if (call.getRecordingOssKey() != null && !call.getRecordingOssKey().isBlank()) {
                boolean removed = storage.delete(call.getRecordingOssKey());
                complianceService.record("RECORDING_PURGE", "SYSTEM", "erase-cascade",
                    event.workspaceId(), event.channelCode(),
                    "callId=" + call.getCallId() + ",key=" + call.getRecordingOssKey()
                        + ",removed=" + removed);
            }
        }
        if (!calls.isEmpty()) {
            callMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsCall>()
                .eq(CsCall::getConversationId, event.conversationId()));
            log.info("[CC] 会话 {} 擦除级联清理通话 {} 通", event.conversationId(), calls.size());
        }
    }
}
