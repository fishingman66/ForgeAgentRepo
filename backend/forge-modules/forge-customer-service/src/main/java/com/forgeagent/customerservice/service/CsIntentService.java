package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsIntent;
import com.forgeagent.customerservice.mapper.CsIntentMapper;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 意图体系(A3,坐席辅助方案第八章):规则打标起步,不引入模型依赖。
 *
 * <p>三层职责:</p>
 * <ol>
 *   <li><b>打标</b>——当前文本命中即判定;无命中时用最近几轮拼接兜底,处理「就这个」这类指代句,
 *       这就是「意图随多轮滚动更新」的落地方式(每条推荐都带当时意图,天然随轮次变化);</li>
 *   <li><b>加权</b>——意图关联的知识库并入检索范围,命中意图关键词的分片再加权排序,
 *       让「客户问退货」时退货类内容优先浮上来;</li>
 *   <li><b>管理</b>——空间级意图的 CRUD;平台级意图(workspace_id 为空)只读,避免被单个空间改坏。</li>
 * </ol>
 *
 * <p>规则口径与 {@code CsQualityService} 一致(KEYWORD / REGEX),运营学一次就能配两处。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsIntentService {

    /** 多轮兜底只看最近几轮:够覆盖指代与追问,又不至于把话题漂移前的意图粘住。 */
    public static final int HISTORY_TURNS = 3;

    /** 命中意图关键词的分片加分(0.05 足以在同源检索结果内重排,又不至于盖过强相关分片)。 */
    public static final double INTENT_BOOST = 0.05;

    private final CsIntentMapper intentMapper;

    /** 打标结果:只带推荐与展示所需字段。 */
    public record Match(Long intentId, String code, String name, String keywords, String ruleType,
                        List<Long> kbIds) {
    }

    /** 管理页列表:平台级 + 本空间,启用停用都在(按优先级排)。 */
    public List<CsIntent> list(Long workspaceId) {
        return intentMapper.selectList(new LambdaQueryWrapper<CsIntent>()
            .and(w -> w.isNull(CsIntent::getWorkspaceId)
                .or().eq(CsIntent::getWorkspaceId, workspaceId))
            .orderByAsc(CsIntent::getPriority)
            .orderByAsc(CsIntent::getId));
    }

    /** 打标用规则集:只看启用行,空间覆盖平台级。 */
    public List<CsIntent> enabledRules(Long workspaceId) {
        return intentMapper.selectList(new LambdaQueryWrapper<CsIntent>()
            .eq(CsIntent::getEnabled, "1")
            .and(w -> w.isNull(CsIntent::getWorkspaceId)
                .or().eq(CsIntent::getWorkspaceId, workspaceId))
            .orderByAsc(CsIntent::getPriority)
            .orderByAsc(CsIntent::getId));
    }

    /**
     * 打标:当前文本优先;未命中再用「最近几轮 + 当前」拼接兜底。
     * 两轮都不命中返回 null(无意图不硬凑,推荐照常生成)。
     */
    public Match classify(Long workspaceId, String text, List<String> recentTexts) {
        List<CsIntent> rules = enabledRules(workspaceId);
        if (rules.isEmpty()) {
            return null;
        }
        CsIntent hit = match(rules, text);
        if (hit == null) {
            hit = match(rules, composeQuery(text, recentTexts));
        }
        if (hit == null) {
            return null;
        }
        return new Match(hit.getId(), hit.getIntentCode(), hit.getIntentName(),
            hit.getKeywords(), hit.getRuleType(), CsVisitorService.parseIds(hit.getKbIds()));
    }

    /**
     * 多轮拼接(纯函数):最近 {@link #HISTORY_TURNS} 轮原话在前、当前句在尾。
     *
     * <p>顺序很重要——「关于退货，就这个」这类指代句只有把历史放在前面,
     * 关键词才不会被当前短句的噪音位置稀释;拼接结果仅用于兜底匹配,
     * 不回写消息、不参与检索。</p>
     */
    public static String composeQuery(String text, List<String> recentTexts) {
        String current = text == null ? "" : text;
        if (recentTexts == null || recentTexts.isEmpty()) {
            return current;
        }
        StringBuilder merged = new StringBuilder();
        int from = Math.max(0, recentTexts.size() - HISTORY_TURNS);
        for (int i = from; i < recentTexts.size(); i++) {
            String line = recentTexts.get(i);
            if (line != null && !line.isBlank()) {
                merged.append(line).append(' ');
            }
        }
        return merged.append(current).toString();
    }

    /** 纯函数:按传入顺序(调用方已按 priority 排序)取首个命中规则。 */
    public static CsIntent match(List<CsIntent> rules, String text) {
        if (rules == null || text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase();
        for (CsIntent rule : rules) {
            if (rule == null || !"1".equals(rule.getEnabled())) {
                continue;
            }
            if (hits(rule, text, lower)) {
                return rule;
            }
        }
        return null;
    }

    private static boolean hits(CsIntent rule, String raw, String lower) {
        String pattern = rule.getKeywords();
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        if ("REGEX".equalsIgnoreCase(rule.getRuleType())) {
            try {
                return Pattern.compile(pattern).matcher(raw).find();
            } catch (PatternSyntaxException e) {
                log.warn("[CS] 非法意图正则 intent={} pattern={}", rule.getIntentCode(), pattern);
                return false;
            }
        }
        for (String keyword : splitKeywords(pattern)) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 意图加权:命中意图关键词的分片加分并重排(纯函数,单测锁定)。
     *
     * <p>只做关键词档——正则档在热路径上逐条编译代价高,且运营用正则表达
     * 「加权」诉求极少;需要时用关键词即可。</p>
     */
    public static List<KnowledgeSearchHitVo> reweight(List<KnowledgeSearchHitVo> hits, CsIntent intent) {
        return intent == null ? hits
            : reweight(hits, intent.getKeywords(), intent.getRuleType());
    }

    /** 打标结果版本的重载:推荐链路拿到的就是 Match。 */
    public static List<KnowledgeSearchHitVo> reweight(List<KnowledgeSearchHitVo> hits, Match match) {
        return match == null ? hits : reweight(hits, match.keywords(), match.ruleType());
    }

    public static List<KnowledgeSearchHitVo> reweight(List<KnowledgeSearchHitVo> hits,
                                                      String keywords, String ruleType) {
        if (hits == null || hits.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        List<String> parts = splitKeywords(keywords);
        if (parts.isEmpty() || "REGEX".equalsIgnoreCase(ruleType)) {
            return hits;
        }
        List<KnowledgeSearchHitVo> ordered = new ArrayList<>(hits);
        for (KnowledgeSearchHitVo hit : ordered) {
            String content = hit.getContent();
            if (content == null) {
                continue;
            }
            for (String keyword : parts) {
                if (content.contains(keyword)) {
                    double base = hit.getScore() == null ? 0.0 : hit.getScore();
                    hit.setScore(Math.min(1.0, Math.round((base + INTENT_BOOST) * 100) / 100.0));
                    break;
                }
            }
        }
        ordered.sort(Comparator.comparingDouble(
            (KnowledgeSearchHitVo hit) -> hit.getScore() == null ? 0.0 : hit.getScore()).reversed());
        return ordered;
    }

    /** 关键词拆分:兼容中文逗号、分号与顿号,运营怎么敲都能用。 */
    public static List<String> splitKeywords(String raw) {
        List<String> keywords = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return keywords;
        }
        for (String part : raw.split("[,;，；、]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                keywords.add(trimmed);
            }
        }
        return keywords;
    }

    /** 新增/编辑:平台级行只读;编码与名称必填,其余给默认值。 */
    public CsIntent save(CsIntent intent, Long workspaceId) {
        if (intent == null || intent.getIntentCode() == null || intent.getIntentCode().isBlank()) {
            throw new ServiceException("意图编码不能为空");
        }
        if (intent.getIntentName() == null || intent.getIntentName().isBlank()) {
            throw new ServiceException("意图名称不能为空");
        }
        if (intent.getKeywords() == null || intent.getKeywords().isBlank()) {
            throw new ServiceException("关键词不能为空");
        }
        intent.setIntentCode(intent.getIntentCode().trim());
        intent.setKeywords(intent.getKeywords().trim());
        intent.setRuleType(intent.getRuleType() == null || intent.getRuleType().isBlank()
            ? "KEYWORD" : intent.getRuleType().toUpperCase());
        intent.setPriority(intent.getPriority() == null ? 100 : intent.getPriority());
        intent.setEnabled(intent.getEnabled() == null ? "1" : intent.getEnabled());
        intent.setUpdateTime(new Date());
        if (intent.getId() == null) {
            intent.setWorkspaceId(workspaceId);
            intent.setCreateTime(new Date());
            intentMapper.insert(intent);
        } else {
            CsIntent existing = intentMapper.selectById(intent.getId());
            requireWritable(existing);
            intent.setWorkspaceId(existing.getWorkspaceId());
            intent.setCreateTime(existing.getCreateTime());
            intentMapper.updateById(intent);
        }
        return intent;
    }

    /** 删除:平台级行不可删;不存在按幂等成功处理。 */
    public boolean remove(Long id) {
        CsIntent existing = intentMapper.selectById(id);
        if (existing == null) {
            return true;
        }
        requireWritable(existing);
        return intentMapper.deleteById(id) > 0;
    }

    private void requireWritable(CsIntent intent) {
        if (intent == null) {
            throw new ServiceException("意图不存在");
        }
        if (intent.getWorkspaceId() == null) {
            throw new ServiceException("平台级意图不可修改");
        }
    }
}
