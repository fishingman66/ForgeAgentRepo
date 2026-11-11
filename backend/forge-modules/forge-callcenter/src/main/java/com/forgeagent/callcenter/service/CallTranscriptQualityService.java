package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.forgeagent.customerservice.domain.CsQualityCheck;
import com.forgeagent.customerservice.domain.CsQualityRule;
import com.forgeagent.customerservice.mapper.CsQualityCheckMapper;
import com.forgeagent.customerservice.mapper.CsQualityRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 转写质检(V2):挂机后对通话全部转写分片跑既有质检规则
 * (agent_cs_quality_rule,口径与 C2 CsQualityService.inspect 一致),
 * 结果写 agent_cs_quality_check(source=VOICE_TRANSCRIPT),运营质检页可直接复审。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallTranscriptQualityService {

    private final CsCallSegmentMapper segmentMapper;
    private final CsQualityRuleMapper ruleMapper;
    private final CsQualityCheckMapper checkMapper;

    /** 工单存在才落质检;返回质检记录 id,无转写/无工单返回 0。 */
    public long inspectCall(CsCall call) {
        if (call.getTicketId() == null) {
            return 0;
        }
        List<CsCallSegment> segments = segmentMapper.selectList(
            new LambdaQueryWrapper<CsCallSegment>()
                .eq(CsCallSegment::getCallId, call.getId()).orderByAsc(CsCallSegment::getId));
        String text = segments.stream()
            .map(CsCallSegment::getText)
            .filter(t -> t != null && !t.isBlank())
            .reduce("", (a, b) -> a + "\n" + b);
        List<CsQualityRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<CsQualityRule>()
            .eq(CsQualityRule::getEnabled, "1")
            .and(w -> w.isNull(CsQualityRule::getWorkspaceId)
                .or().eq(CsQualityRule::getWorkspaceId, call.getWorkspaceId())));
        int delta = 0;
        List<String> hits = new ArrayList<>();
        for (CsQualityRule rule : rules) {
            if (matches(rule, text)) {
                delta += rule.getScoreDelta() == null ? 0 : rule.getScoreDelta();
                hits.add(rule.getPattern());
            }
        }
        CsQualityCheck check = new CsQualityCheck();
        check.setMessageId(null);
        check.setTicketId(call.getTicketId());
        check.setWorkspaceId(call.getWorkspaceId());
        check.setScore(Math.max(0, Math.min(100, 100 + delta)));
        check.setHitRules(String.join(",", hits));
        check.setSource("VOICE_TRANSCRIPT");
        check.setReviewStatus("PENDING");
        check.setCreateTime(new Date());
        checkMapper.insert(check);
        return check.getId();
    }

    private boolean matches(CsQualityRule rule, String content) {
        String pattern = rule.getPattern();
        if (pattern == null || pattern.isBlank() || content == null || content.isBlank()) {
            return false;
        }
        if ("REGEX".equalsIgnoreCase(rule.getRuleType())) {
            try {
                return Pattern.compile(pattern).matcher(content).find();
            } catch (PatternSyntaxException e) {
                log.warn("[CC] 非法质检正则 pattern={}", pattern);
                return false;
            }
        }
        return content.contains(pattern);
    }
}
