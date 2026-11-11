package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.forgeagent.callcenter.common.CsPiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 转写入库(V1):asr_result / bot_utterance 写 agent_cc_call_segment。
 * 文本统一过公共层 CsPiiMasker 后落库,号码与敏感信息不进明文表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class TranscriptIngestService {

    private final CsCallSegmentMapper segmentMapper;

    /** 写入一条转写分片;正文为空返回 null(A2 据此跳过坐席推荐)。 */
    public CsCallSegment ingest(CsCall call, CsVoiceEvent event) {
        String text = event.str("text");
        if (text == null || text.isBlank()) {
            return null;
        }
        CsCallSegment segment = new CsCallSegment();
        segment.setCallId(call.getId());
        String speaker = event.str("speaker");
        segment.setSpeaker(speaker == null || speaker.isBlank() ? "caller" : speaker);
        segment.setText(CsPiiMasker.mask(text));
        segment.setStartMs(event.longValue("startMs"));
        segment.setEndMs(event.longValue("endMs"));
        BigDecimal confidence = null;
        Object raw = event.content().get("confidence");
        if (raw instanceof Number number) {
            confidence = BigDecimal.valueOf(number.doubleValue());
        }
        segment.setAsrConfidence(confidence);
        segment.setIsFinal("true".equalsIgnoreCase(String.valueOf(event.content().get("isFinal"))) ? "1" : "0");
        segment.setCreateTime(new Date());
        segmentMapper.insert(segment);
        return segment;
    }
}
