package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.forgeagent.callcenter.common.CsPiiMasker;
import com.forgeagent.callcenter.insight.InsightPipeline;
import com.forgeagent.callcenter.route.DirectAnswerRouter;
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
    /** C4 洞察管道:旁路异步,主叫终稿触发情绪评估(节流在管道内)。 */
    private final InsightPipeline insightPipeline;
    /** C5 无 IVR 直接应答分流:渠道开启时才动作,未开启零开销。 */
    private final DirectAnswerRouter directAnswerRouter;

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
        // 洞察挂点在入库之后:管道只读已脱敏文本,失败不影响转写入库
        try {
            insightPipeline.onSegment(call, segment);
        } catch (Exception e) {
            log.warn("[CC] 洞察管道触发失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
        // C5 直接应答分流:同步决策 + 动作(转接要赶在通话结束前),决策留痕异步
        try {
            directAnswerRouter.onSegment(call, segment);
        } catch (Exception e) {
            log.warn("[CC] 直接应答分流失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
        return segment;
    }
}
