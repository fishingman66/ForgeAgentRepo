package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import com.forgeagent.callcenter.gateway.CsVoiceGateway;
import com.forgeagent.callcenter.storage.RecordingStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 录音入库(V1,D6):call.record.ready -> 取回一次 -> 本地 OSS -> 只存对象键。
 *
 * <p>OSS 不可用或录音地址不支持时降级:仅登记 recordingId 并告警,不让回调失败,
 * 也不向响应方泄露内部细节。对象键前缀 callcenter/recording/,与业务附件隔离。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class RecordingIngestService {

    private final CsVoiceGateway gateway;
    private final CallcenterProperties properties;
    private final RecordingStorage storage;

    public void ingest(CsCall call, CsVoiceEvent event) {
        String recordingId = event.str("recordingId");
        String url = event.str("recordingUrl");
        call.setRecordingId(recordingId);
        call.setRecordingReadyAt(new Date());
        try {
            byte[] data = gateway.fetchRecording(url);
            String objectKey = "callcenter/recording/" + call.getCallId() + "/"
                + (recordingId == null ? "recording" : recordingId) + ".wav";
            storage.save(objectKey, data);
            call.setRecordingOssKey(objectKey);
            long days = Math.max(1, properties.getRecordingRetentionDays());
            call.setRecordingRetainUntil(new Date(System.currentTimeMillis() + days * 86_400_000L));
            log.info("[CC] 录音已入库 callId={} key={} bytes={}", call.getCallId(), objectKey, data.length);
        } catch (Exception e) {
            log.error("[CC] 录音入库降级 callId={} url={}: {}", call.getCallId(), url, e.getMessage());
        }
    }
}
