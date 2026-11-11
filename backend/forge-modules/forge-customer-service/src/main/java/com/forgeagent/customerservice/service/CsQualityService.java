package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
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
 * 会话质检(C2)。规则可配置,平台级规则(workspace_id IS NULL)对所有空间生效;
 * KEYWORD / REGEX 两档本地判定,LLM 抽检档留待有真实模型预算后启用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsQualityService {

    private final CsQualityRuleMapper ruleMapper;
    private final CsQualityCheckMapper checkMapper;

    /** 对一条坐席消息跑规则并落质检结果;无命中规则也留一条满分记录便于追溯。 */
    public CsQualityCheck inspect(Long messageId, Long ticketId, Long workspaceId, String content) {
        List<CsQualityRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<CsQualityRule>()
            .eq(CsQualityRule::getEnabled, "1")
            .and(w -> w.isNull(CsQualityRule::getWorkspaceId)
                .or().eq(CsQualityRule::getWorkspaceId, workspaceId)));
        int delta = 0;
        List<String> hits = new ArrayList<>();
        if (content != null) {
            for (CsQualityRule rule : rules) {
                if (matches(rule, content)) {
                    delta += rule.getScoreDelta() == null ? 0 : rule.getScoreDelta();
                    hits.add(rule.getPattern());
                }
            }
        }
        CsQualityCheck check = new CsQualityCheck();
        check.setMessageId(messageId);
        check.setTicketId(ticketId);
        check.setWorkspaceId(workspaceId);
        check.setScore(Math.max(0, Math.min(100, 100 + delta)));
        check.setHitRules(String.join(",", hits));
        check.setSource("AUTO");
        check.setReviewStatus("PENDING");
        check.setCreateTime(new Date());
        checkMapper.insert(check);
        return check;
    }

    private boolean matches(CsQualityRule rule, String content) {
        String pattern = rule.getPattern();
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        if ("REGEX".equalsIgnoreCase(rule.getRuleType())) {
            try {
                return Pattern.compile(pattern).matcher(content).find();
            } catch (PatternSyntaxException e) {
                log.warn("[CS] 非法质检正则 pattern={}", pattern);
                return false;
            }
        }
        // LLM 档当前不启用,按关键词处理避免静默放行
        return content.contains(pattern);
    }

    public List<CsQualityRule> rules(Long workspaceId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<CsQualityRule>()
            .and(w -> w.isNull(CsQualityRule::getWorkspaceId)
                .or().eq(CsQualityRule::getWorkspaceId, workspaceId))
            .orderByAsc(CsQualityRule::getId));
    }

    public CsQualityRule saveRule(CsQualityRule rule) {
        if (rule.getPattern() == null || rule.getPattern().isBlank()) {
            throw new ServiceException("规则内容不能为空");
        }
        rule.setEnabled(rule.getEnabled() == null ? "1" : rule.getEnabled());
        rule.setRuleType(rule.getRuleType() == null ? "KEYWORD" : rule.getRuleType());
        rule.setCreateTime(new Date());
        if (rule.getId() == null) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }
        return rule;
    }

    public List<CsQualityCheck> checks(Long workspaceId) {
        return checkMapper.selectList(new LambdaQueryWrapper<CsQualityCheck>()
            .eq(CsQualityCheck::getWorkspaceId, workspaceId)
            .orderByDesc(CsQualityCheck::getId).last("limit 200"));
    }

    public CsQualityCheck review(Long id, String status) {
        CsQualityCheck check = checkMapper.selectById(id);
        if (check == null) {
            throw new ServiceException("质检记录不存在");
        }
        check.setReviewStatus(status);
        checkMapper.updateById(check);
        return check;
    }
}
