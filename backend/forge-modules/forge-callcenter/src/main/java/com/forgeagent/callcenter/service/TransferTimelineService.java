package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 转接时间轴(C8,方案 docs/07 §三 P2):把「转人工发起 → 坐席接通」补进同一张分片表,
 * 对齐 SmartCall {@code call_transfer_info} 的 ready→ring→answer→hangup 时间轴。
 *
 * <p>为什么复用 {@code agent_cc_call_segment} 而不另建表:弹屏、质检、画像都按 call 顺序
 * 读这张表拼时间轴,加一个 {@code segment_kind} 就能让转接等待时长与转写在同一时间轴上排序,
 * 不必让每个消费方做双表归并。</p>
 *
 * <p>幂等:一通电话只登记一条 RING 与一条 CONNECT(多次转接的细分留后续,当前 8 态状态机
 * 也只有一次转人工跃迁);重复事件在上游已被指纹去重,这里再做一层存在性判断兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class TransferTimelineService {

    /** 转写段。 */
    public static final String KIND_TRANSCRIPT = "TRANSCRIPT";
    /** 转人工发起(进队列)。 */
    public static final String KIND_RING = "TRANSFER_RING";
    /** 坐席接通。 */
    public static final String KIND_CONNECT = "TRANSFER_CONNECT";

    private final CsCallSegmentMapper segmentMapper;

    /** 转人工:登记 RING 段,记录目标队列与发起时间。 */
    public void recordRing(CsCall call, CsVoiceEvent event) {
        String queue = event.str("transferTo");
        if (queue == null || queue.isBlank()) {
            queue = event.str("queue");
        }
        write(call, event, KIND_RING, "转人工 → 队列 " + (queue == null || queue.isBlank() ? "(未声明)" : queue));
    }

    /** 坐席状态键名:AICC 用 readyState,FS 归一化用 state,两路都要认。 */
    public static String seatState(CsVoiceEvent event) {
        String state = event.str("readyState");
        return state == null || state.isBlank() ? event.str("state") : state;
    }

    /** 坐席接通:登记 CONNECT 段,记录工号。 */
    public void recordConnect(CsCall call, CsVoiceEvent event) {
        String workNo = event.workNo() == null || event.workNo().isBlank()
            ? call.getWorkNo() : event.workNo();
        write(call, event, KIND_CONNECT,
            "坐席接通 " + (workNo == null || workNo.isBlank() ? "(工号未知)" : workNo));
    }

    private void write(CsCall call, CsVoiceEvent event, String kind, String text) {
        if (call == null || call.getId() == null) {
            return;
        }
        try {
            Long existing = segmentMapper.selectCount(new LambdaQueryWrapper<CsCallSegment>()
                .eq(CsCallSegment::getCallId, call.getId())
                .eq(CsCallSegment::getSegmentKind, kind));
            if (existing != null && existing > 0) {
                return;
            }
            CsCallSegment segment = new CsCallSegment();
            segment.setCallId(call.getId());
            // system 说话人:既不进 ASR 置信度统计,也不会被当成主叫文本参与情绪/提取
            segment.setSpeaker("system");
            segment.setText(text);
            segment.setSegmentKind(kind);
            segment.setStartMs(event.eventGenTime());
            segment.setEndMs(event.eventGenTime());
            segment.setIsFinal("1");
            segment.setCreateTime(new Date());
            segmentMapper.insert(segment);
        } catch (Exception e) {
            // 时间轴缺一段不影响通话主链路,只记日志
            log.warn("[CC] 转接时间轴写入失败 callId={} kind={}: {}", call.getCallId(), kind, e.getMessage());
        }
    }
}
