package com.forgeagent.callcenter.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import com.forgeagent.callcenter.storage.RecordingStorage;
import com.forgeagent.customerservice.compliance.CsComplianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 录音留存清理(V1,D6):到期删除 OSS 对象、清空对象键并写 RECORDING_PURGE 审计。
 * 转写分片按 C4 文本留存口径由既有任务处理,本任务只管音频对象。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallRetentionTask {

    private final CsCallMapper callMapper;
    private final CallcenterProperties properties;
    private final ObjectProvider<CsComplianceService> complianceProvider;
    private final RecordingStorage storage;

    @Scheduled(cron = "0 40 3 * * ?")
    public void purgeExpired() {
        List<CsCall> expired = callMapper.selectList(new LambdaQueryWrapper<CsCall>()
            .isNotNull(CsCall::getRecordingOssKey)
            .lt(CsCall::getRecordingRetainUntil, new Date())
            .last("limit 100"));
        if (expired.isEmpty()) {
            return;
        }
        int purged = 0;
        for (CsCall call : expired) {
            String key = call.getRecordingOssKey();
            boolean removed = storage.delete(key);
            if (!removed) {
                log.warn("[CC] 录音对象删除失败 key={}", key);
            }
            call.setRecordingOssKey(null);
            call.setUpdateTime(new Date());
            callMapper.updateById(call);
            CsComplianceService compliance = complianceProvider.getIfAvailable();
            if (compliance != null) {
                compliance.record("RECORDING_PURGE", "system", "CallRetentionTask",
                    call.getWorkspaceId(), call.getChannelCode(),
                    "callId=" + call.getCallId() + ",key=" + key);
            }
            purged++;
        }
        log.info("[CC] 录音留存清理完成 {} 条", purged);
    }
}
