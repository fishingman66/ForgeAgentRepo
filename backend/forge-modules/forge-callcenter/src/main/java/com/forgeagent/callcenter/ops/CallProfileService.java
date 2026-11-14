package com.forgeagent.callcenter.ops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallInsight;
import com.forgeagent.callcenter.domain.CsCallProfile;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.insight.InsightKind;
import com.forgeagent.callcenter.mapper.CsCallInsightMapper;
import com.forgeagent.callcenter.mapper.CsCallProfileMapper;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.forgeagent.callcenter.service.TransferTimelineService;
import com.forgeagent.customerservice.domain.CsQualityCheck;
import com.forgeagent.customerservice.mapper.CsQualityCheckMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通话画像快照(C8,方案 docs/07 §三 P2):把一通电话的结论性信息压成一行。
 *
 * <p>定位是<b>洞察的下游产物</b>:只读 {@code agent_cc_call_insight} 与质检结果,
 * 不做二次判断、不生成新文本——摘要由确定性规则拼装,口径可逐字复算。</p>
 *
 * <p>幂等:唯一键 {@code call_id},重跑只更新同一行(挂机后洞察可能重放)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallProfileService {

    /** 语音质检来源(与质检服务同一口径)。 */
    private static final String QUALITY_SOURCE = "VOICE_TRANSCRIPT";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CsCallInsightMapper insightMapper;
    private final CsCallSegmentMapper segmentMapper;
    private final CsCallProfileMapper profileMapper;
    private final CsQualityCheckMapper qualityCheckMapper;

    /** 生成或刷新画像;任何异常只记日志,不影响挂机主链路。 */
    public CsCallProfile build(CsCall call) {
        if (call == null || call.getId() == null) {
            return null;
        }
        try {
            List<CsCallInsight> insights = loadInsights(call);
            List<CsCallSegment> segments = loadSegments(call);
            String intent = intentOf(insights);
            Sentiment worst = worstSentiment(insights);
            Map<String, String> extracted = extractedOf(insights);
            int callerTurns = (int) segments.stream()
                .filter(s -> "caller".equalsIgnoreCase(s.getSpeaker()))
                .filter(s -> s.getSegmentKind() == null
                    || TransferTimelineService.KIND_TRANSCRIPT.equals(s.getSegmentKind()))
                .count();
            Integer quality = qualityScore(call);

            CsCallProfile profile = new CsCallProfile();
            profile.setCallId(call.getId());
            profile.setWorkspaceId(call.getWorkspaceId());
            profile.setChannelCode(call.getChannelCode());
            profile.setCallerNumber(call.getCallerNumber());
            profile.setIntentCode(intent);
            profile.setSentimentLabel(worst == null ? null : worst.label());
            profile.setSentimentScore(worst == null ? null : worst.score());
            profile.setQualityScore(quality);
            profile.setDurationSec(durationSec(call, segments));
            profile.setTurnCount(callerTurns);
            profile.setTransferFlag(isBlank(call.getTransferTo()) ? "0" : "1");
            profile.setResolvedFlag(call.getTicketId() == null ? "1" : "0");
            profile.setTags(tagsOf(profile.getSentimentLabel(), profile.getTransferFlag(), profile.getResolvedFlag()));
            profile.setSummary(summaryOf(intent, profile.getDurationSec(), worst,
                call.getTransferTo(), call.getAnswerAt() != null));
            profile.setPayload(payloadJson(intent, worst, extracted, callerTurns, sentimentDistribution(insights)));
            return upsert(profile);
        } catch (Exception e) {
            log.warn("[CC] 通话画像生成失败 callId={}: {}", call.getCallId(), e.getMessage());
            return null;
        }
    }

    /** 按 call_id 幂等写入。 */
    private CsCallProfile upsert(CsCallProfile profile) {
        Date now = new Date();
        CsCallProfile existing = profileMapper.selectOne(new LambdaQueryWrapper<CsCallProfile>()
            .eq(CsCallProfile::getCallId, profile.getCallId()).last("limit 1"));
        if (existing == null) {
            profile.setCreateTime(now);
            profile.setUpdateTime(now);
            try {
                profileMapper.insert(profile);
                return profile;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发写同一通话:回查后转更新(唯一键兜底)
                existing = profileMapper.selectOne(new LambdaQueryWrapper<CsCallProfile>()
                    .eq(CsCallProfile::getCallId, profile.getCallId()).last("limit 1"));
                if (existing == null) {
                    throw e;
                }
            }
        }
        profile.setId(existing.getId());
        profile.setCreateTime(existing.getCreateTime());
        profile.setUpdateTime(now);
        profileMapper.updateById(profile);
        return profile;
    }

    private List<CsCallInsight> loadInsights(CsCall call) {
        try {
            List<CsCallInsight> rows = insightMapper.selectList(new LambdaQueryWrapper<CsCallInsight>()
                .eq(CsCallInsight::getCallId, call.getId()));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.debug("[CC] 画像读取洞察降级 callId={}: {}", call.getCallId(), e.getMessage());
            return List.of();
        }
    }

    private List<CsCallSegment> loadSegments(CsCall call) {
        try {
            List<CsCallSegment> rows = segmentMapper.selectList(new LambdaQueryWrapper<CsCallSegment>()
                .eq(CsCallSegment::getCallId, call.getId()).orderByAsc(CsCallSegment::getId));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 挂机全文意图只在 item_key=final 那一行(C5 的 direct-* 是逐片决策,不是画像口径)。 */
    private static String intentOf(List<CsCallInsight> insights) {
        for (CsCallInsight row : insights) {
            if (InsightKind.INTENT.name().equals(row.getKind()) && "final".equals(row.getItemKey())) {
                return row.getLabel();
            }
        }
        return null;
    }

    /** 通话内最负面的一次(score 最小);无情绪行返回 null。 */
    private static Sentiment worstSentiment(List<CsCallInsight> insights) {
        Sentiment worst = null;
        for (CsCallInsight row : insights) {
            if (!InsightKind.SENTIMENT.name().equals(row.getKind()) || row.getScore() == null) {
                continue;
            }
            if (worst == null || row.getScore().compareTo(worst.score()) < 0) {
                worst = new Sentiment(row.getLabel(), row.getScore());
            }
        }
        return worst;
    }

    private static Map<String, String> extractedOf(List<CsCallInsight> insights) {
        Map<String, String> extracted = new LinkedHashMap<>();
        for (CsCallInsight row : insights) {
            if (!InsightKind.EXTRACT.name().equals(row.getKind()) || row.getPayload() == null) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(row.getPayload()).get("value");
                if (node != null && !node.isNull()) {
                    extracted.put(row.getItemKey(), node.asText());
                }
            } catch (Exception ignored) {
                // 单条 payload 坏了不影响整张画像
            }
        }
        return extracted;
    }

    private static Map<String, Integer> sentimentDistribution(List<CsCallInsight> insights) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (CsCallInsight row : insights) {
            if (InsightKind.SENTIMENT.name().equals(row.getKind())) {
                distribution.merge(row.getLabel() == null ? "ERROR" : row.getLabel(), 1, Integer::sum);
            }
        }
        return distribution;
    }

    private Integer qualityScore(CsCall call) {
        if (call.getTicketId() == null) {
            return null;
        }
        try {
            CsQualityCheck check = qualityCheckMapper.selectOne(new LambdaQueryWrapper<CsQualityCheck>()
                .eq(CsQualityCheck::getWorkspaceId, call.getWorkspaceId())
                .eq(CsQualityCheck::getTicketId, call.getTicketId())
                .eq(CsQualityCheck::getSource, QUALITY_SOURCE)
                .last("limit 1"));
            return check == null ? null : check.getScore();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 时长:优先权威字段,否则用分片时间线兜底(挂机瞬间 CDR 可能还没回填)。
     * 两者都没有时返回 null —— 写 0 会让摘要出现「通话 0 秒」这种噪声。
     */
    static Integer durationSec(CsCall call, List<CsCallSegment> segments) {
        if (call.getDurationSec() != null && call.getDurationSec() > 0) {
            return call.getDurationSec();
        }
        long maxEnd = 0L;
        for (CsCallSegment segment : segments) {
            if (segment.getEndMs() != null && segment.getEndMs() > maxEnd) {
                maxEnd = segment.getEndMs();
            }
        }
        return maxEnd > 0 ? (int) (maxEnd / 1000L) : null;
    }

    /** 标签:负面 / 转人工 / 未解决;只为可读性,不参与任何统计口径。 */
    static String tagsOf(String sentimentLabel, String transferFlag, String resolvedFlag) {
        List<String> tags = new ArrayList<>();
        if ("NEGATIVE".equalsIgnoreCase(sentimentLabel)) {
            tags.add("负面");
        }
        if ("1".equals(transferFlag)) {
            tags.add("转人工");
        }
        if ("0".equals(resolvedFlag)) {
            tags.add("未解决");
        }
        return String.join(",", tags);
    }

    /**
     * 摘要:确定性拼装,缺什么就不写什么,不补不编。
     * 未接通(呼损)的通话不能写「机器人处理」——那是另一种事实错误。
     */
    static String summaryOf(String intent, Integer durationSec, Sentiment sentiment,
                            String transferTo, boolean answered) {
        StringBuilder sb = new StringBuilder();
        sb.append(intent == null ? "意图未识别" : "意图 " + intent);
        if (durationSec != null) {
            sb.append("；通话 ").append(durationSec).append(" 秒");
        }
        if (sentiment != null && sentiment.label() != null) {
            sb.append("；情绪 ").append(sentiment.label());
        }
        if (!isBlank(transferTo)) {
            sb.append("；已转人工 ").append(transferTo);
        } else if (answered) {
            sb.append("；机器人处理");
        } else {
            sb.append("；未接通");
        }
        return sb.length() > 500 ? sb.substring(0, 500) : sb.toString();
    }

    private static String payloadJson(String intent, Sentiment sentiment, Map<String, String> extracted,
                                      int callerTurns, Map<String, Integer> distribution) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", intent);
        if (sentiment != null) {
            payload.put("sentiment", Map.of("label", sentiment.label(),
                "score", sentiment.score().doubleValue()));
        }
        payload.put("sentimentDistribution", distribution);
        payload.put("extract", extracted);
        payload.put("callerTurns", callerTurns);
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 情绪结论(标签 + 分值),内部传递用。 */
    record Sentiment(String label, BigDecimal score) {
    }
}
