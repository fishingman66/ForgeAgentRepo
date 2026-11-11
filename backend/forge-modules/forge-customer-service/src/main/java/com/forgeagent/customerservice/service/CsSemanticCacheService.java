package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsSemanticCache;
import com.forgeagent.customerservice.mapper.CsSemanticCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义缓存(P1,差距分析 5.1):只缓存「通用问答」,三道闸门缺一不可。
 *
 * <p><b>为什么必须三道闸门</b>:无差别缓存会把 A 用户的订单信息答给 B。本实现的三道是</p>
 * <ol>
 *   <li><b>意图白名单</b>:只有配置里的意图(默认退换/发票)才缓存,意图为空不缓存——
 *       订单进度、个人信息这类永远个性化,不进缓存;</li>
 *   <li><b>个人标识过滤</b>:问题或答案含 6 位以上连续数字(订单号/手机号)直接跳过;</li>
 *   <li><b>分区</b>:查询与写入都带 {@code workspace_id + channel_code},SQL 层强制隔离。</li>
 * </ol>
 *
 * <p>另外两条工程约定:缓存的是<b>出站敏感词过滤之后</b>的答案(调用方保证);
 * 近似匹配阈值默认 0.95,「怎么退货」与「怎么换货」到不了这个分——宁可少命中,不可答错。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsSemanticCacheService {

    /** 近似匹配时最多比较多少条(同分区)。 */
    private static final int SCAN_LIMIT = 200;
    /** 6 位以上连续数字视为个人标识。 */
    private static final Pattern PERSONAL_ID = Pattern.compile("\\d{6,}");

    public record Lookup(boolean hit, String answer, String intent) {
        public static Lookup miss() {
            return new Lookup(false, null, null);
        }
    }

    private final CsSemanticCacheMapper cacheMapper;
    private final CsIntentService intentService;
    private final CsModuleProperties properties;

    /** 查缓存:未开启或未过闸门直接 miss;命中后累加 hits 用于运营判断价值。 */
    public Lookup find(Long workspaceId, String channelCode, String question) {
        if (!enabled() || question == null || question.isBlank()) {
            return Lookup.miss();
        }
        String normalized = normalize(question);
        if (normalized.isEmpty() || hasPersonalIdentifier(normalized)) {
            return Lookup.miss();
        }
        CsIntentService.Match intent = intentService.classify(workspaceId, question, List.of());
        if (!cacheableIntent(intent)) {
            return Lookup.miss();
        }
        CsSemanticCache exact = cacheMapper.selectOne(baseQuery(workspaceId, channelCode)
            .eq(CsSemanticCache::getQuestionHash, hash(normalized)).last("limit 1"));
        if (usable(exact)) {
            touch(exact);
            return new Lookup(true, exact.getAnswer(), exact.getIntent());
        }
        List<CsSemanticCache> recent = cacheMapper.selectList(baseQuery(workspaceId, channelCode)
            .orderByDesc(CsSemanticCache::getUpdateTime)
            .last("limit " + SCAN_LIMIT));
        double threshold = threshold();
        CsSemanticCache best = null;
        double bestScore = 0;
        for (CsSemanticCache row : recent) {
            if (!usable(row) || row.getQuestion() == null) {
                continue;
            }
            double score = similarity(normalized, row.getQuestion());
            if (score >= threshold && score > bestScore) {
                best = row;
                bestScore = score;
            }
        }
        if (best == null) {
            return Lookup.miss();
        }
        touch(best);
        log.debug("[CS] 语义缓存近似命中 score={} question={}", bestScore, question);
        return new Lookup(true, best.getAnswer(), best.getIntent());
    }

    /** 写缓存:问题与答案都要过闸门;同 hash 覆盖更新(答案可能因知识更新而变化)。 */
    public void store(Long workspaceId, String channelCode, String question, String answer) {
        if (!enabled() || question == null || answer == null || answer.isBlank()) {
            return;
        }
        String normalized = normalize(question);
        if (normalized.isEmpty() || hasPersonalIdentifier(normalized) || hasPersonalIdentifier(answer)) {
            return;
        }
        CsIntentService.Match intent = intentService.classify(workspaceId, question, List.of());
        if (!cacheableIntent(intent)) {
            return;
        }
        String questionHash = hash(normalized);
        CsSemanticCache existing = cacheMapper.selectOne(baseQuery(workspaceId, channelCode)
            .eq(CsSemanticCache::getQuestionHash, questionHash).last("limit 1"));
        Date now = new Date();
        if (existing != null) {
            existing.setAnswer(answer);
            existing.setIntent(intent.code());
            existing.setUpdateTime(now);
            cacheMapper.updateById(existing);
            return;
        }
        CsSemanticCache row = new CsSemanticCache();
        row.setWorkspaceId(workspaceId);
        row.setChannelCode(channelCode);
        row.setQuestionHash(questionHash);
        row.setQuestion(normalized);
        row.setAnswer(answer);
        row.setIntent(intent.code());
        row.setHits(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        cacheMapper.insert(row);
    }

    /** 运营查看:按分区倒序列出。 */
    public List<CsSemanticCache> list(Long workspaceId, String channelCode, int limit) {
        return cacheMapper.selectList(baseQuery(workspaceId, channelCode)
            .orderByDesc(CsSemanticCache::getUpdateTime)
            .last("limit " + Math.max(1, Math.min(200, limit))));
    }

    public boolean remove(Long id) {
        return id != null && cacheMapper.deleteById(id) > 0;
    }

    /** 清空一个分区的缓存(知识更新后运营手动失效)。 */
    public int clearScope(Long workspaceId, String channelCode) {
        return cacheMapper.delete(baseQuery(workspaceId, channelCode));
    }

    /**
     * 三道闸门中的前两道(纯函数,单测锁定):意图在白名单,且问题/答案都不含个人标识。
     * 第三道分区由 SQL 条件保证。
     */
    public static boolean cacheable(String question, String answer, String intentCode, String whitelist) {
        return intentAllowed(intentCode, whitelist)
            && !hasPersonalIdentifier(question) && !hasPersonalIdentifier(answer);
    }

    /** 意图白名单判定:空意图、空白名单一律不允许。 */
    public static boolean intentAllowed(String intentCode, String whitelist) {
        if (intentCode == null || intentCode.isBlank() || whitelist == null || whitelist.isBlank()) {
            return false;
        }
        for (String item : CsIntentService.splitKeywords(whitelist)) {
            if (item.equalsIgnoreCase(intentCode)) {
                return true;
            }
        }
        return false;
    }

    /** 6 位以上连续数字即视为个人标识。 */
    public static boolean hasPersonalIdentifier(String text) {
        return text != null && PERSONAL_ID.matcher(text).find();
    }

    /** 归一化:小写 + 只保留字母/数字,抹掉空白与中英文标点。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char ch : text.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 相似度(1 - 编辑距离/最长长度);归一化后完全相同返回 1。 */
    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private LambdaQueryWrapper<CsSemanticCache> baseQuery(Long workspaceId, String channelCode) {
        return new LambdaQueryWrapper<CsSemanticCache>()
            .eq(CsSemanticCache::getWorkspaceId, workspaceId)
            .eq(CsSemanticCache::getChannelCode, channelCode);
    }

    private boolean usable(CsSemanticCache row) {
        return row != null && row.getAnswer() != null && !row.getAnswer().isBlank()
            && !hasPersonalIdentifier(row.getAnswer());
    }

    private void touch(CsSemanticCache row) {
        cacheMapper.update(null, new LambdaUpdateWrapper<CsSemanticCache>()
            .eq(CsSemanticCache::getId, row.getId())
            .setSql("hits = hits + 1")
            .set(CsSemanticCache::getLastHitAt, new Date()));
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(properties.getSemanticCacheEnabled());
    }

    private double threshold() {
        Double value = properties.getSemanticCacheThreshold();
        return value == null || value <= 0 || value > 1 ? 0.95 : value;
    }

    private boolean cacheableIntent(CsIntentService.Match intent) {
        return intent != null && intentAllowed(intent.code(), properties.getSemanticCacheIntents());
    }

    private static String hash(String normalized) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("缓存问题摘要计算失败", e);
        }
    }
}
