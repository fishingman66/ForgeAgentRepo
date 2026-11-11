package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsAssistMetricDaily;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.domain.CsSeatSuggestion;
import com.forgeagent.customerservice.mapper.CsAssistMetricDailyMapper;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsKnowledgeAnswerMapper;
import com.forgeagent.customerservice.mapper.CsSeatSuggestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 坐席辅助度量(A5,方案第八章):采纳率、意图覆盖、零采纳回流。
 *
 * <p><b>采纳率口径(用户 2026-11-10 明确):按推荐条数算</b>——
 * adopted='1' 的条数 / 推荐总条数。一条推荐上先 REPLY 再 RESOLVE 也只算一条采纳,
 * 动作次数单独记 action_count,不与采纳率混用。</p>
 *
 * <p>零采纳回流把「连续的、没被采纳的」推荐变成待审 FAQ:同一会话最近
 * {@link #CONSECUTIVE_WINDOW} 条推荐全部未采纳,且该推荐已超过回流窗口,
 * 就用触发原文生成 PENDING 答案(source=seat_assist),运营在 FAQ 页补答案后生效。
 * 只回流一次(recycled 标记),同问题按 question_hash 去重。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsAssistMetricService {

    /** 连续零采纳的判定窗口:同会话最近 3 条推荐全未采纳才回流,避免单条偶发噪音。 */
    public static final int CONSECUTIVE_WINDOW = 3;
    /** 待审 FAQ 的问题长度上限。 */
    public static final int QUESTION_LIMIT = 200;
    /** 回流来源标识,与零命中的 zero_hit 并列,便于运营区分数据来源。 */
    public static final String RECYCLE_SOURCE = "seat_assist";

    private final CsSeatSuggestionMapper suggestionMapper;
    private final CsAssistMetricDailyMapper metricMapper;
    private final CsConversationMapper conversationMapper;
    private final CsKnowledgeAnswerMapper answerMapper;
    private final CsChannelMapper channelMapper;
    private final CsModuleProperties properties;

    /** 重算某日指标(幂等 upsert);在看板打开或手动刷新时调用。 */
    public CsAssistMetricDaily rebuild(Long workspaceId, Date statDate) {
        Date[] range = dayRange(statDate);
        Date start = range[0];
        Date end = range[1];

        List<Long> convIds = conversationMapper.selectList(new LambdaQueryWrapper<CsConversation>()
                .eq(CsConversation::getWorkspaceId, workspaceId)
                .ge(CsConversation::getCreateTime, start)
                .lt(CsConversation::getCreateTime, end))
            .stream().map(CsConversation::getId).toList();

        int suggestionCount = 0;
        int adoptedCount = 0;
        int actionCount = 0;
        int intentCount = 0;
        if (!convIds.isEmpty()) {
            List<CsSeatSuggestion> rows = suggestionMapper.selectList(
                new LambdaQueryWrapper<CsSeatSuggestion>()
                    .in(CsSeatSuggestion::getConversationId, convIds)
                    .ge(CsSeatSuggestion::getCreateTime, start)
                    .lt(CsSeatSuggestion::getCreateTime, end));
            suggestionCount = rows.size();
            for (CsSeatSuggestion row : rows) {
                if ("1".equals(row.getAdopted())) {
                    adoptedCount++;
                }
                if (row.getAdoptAction() != null && row.getAdoptAction().contains("action:")) {
                    actionCount++;
                }
                if (row.getIntent() != null && !row.getIntent().isBlank()) {
                    intentCount++;
                }
            }
        }
        Long recycledRaw = answerMapper.selectCount(new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId)
            .eq(CsKnowledgeAnswer::getSource, RECYCLE_SOURCE)
            .ge(CsKnowledgeAnswer::getCreateTime, start)
            .lt(CsKnowledgeAnswer::getCreateTime, end));
        int recycledCount = recycledRaw == null ? 0 : recycledRaw.intValue();

        CsAssistMetricDaily metric = metricMapper.selectOne(new LambdaQueryWrapper<CsAssistMetricDaily>()
            .eq(CsAssistMetricDaily::getWorkspaceId, workspaceId)
            .eq(CsAssistMetricDaily::getStatDate, start).last("limit 1"));
        boolean insert = metric == null;
        if (insert) {
            metric = new CsAssistMetricDaily();
            metric.setWorkspaceId(workspaceId);
            metric.setStatDate(start);
            metric.setCreateTime(new Date());
        }
        metric.setSuggestionCount(suggestionCount);
        metric.setAdoptedCount(adoptedCount);
        metric.setActionCount(actionCount);
        metric.setIntentCount(intentCount);
        metric.setRecycledCount(recycledCount);
        metric.setUpdateTime(new Date());
        if (insert) {
            metricMapper.insert(metric);
        } else {
            metricMapper.updateById(metric);
        }
        return metric;
    }

    /**
     * 看板数据:最近 N 天日指标 + 汇总。
     * 汇总的采纳率按条数重新算,不取各日均值——日均值会让低量天数权重失真。
     */
    public Map<String, Object> overview(Long workspaceId, int days) {
        int span = Math.max(1, Math.min(90, days));
        List<CsAssistMetricDaily> rows = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -i);
            rows.add(rebuild(workspaceId, cal.getTime()));
        }
        long suggestionTotal = rows.stream().mapToLong(r -> nz(r.getSuggestionCount())).sum();
        long adoptedTotal = rows.stream().mapToLong(r -> nz(r.getAdoptedCount())).sum();
        long actionTotal = rows.stream().mapToLong(r -> nz(r.getActionCount())).sum();
        long intentTotal = rows.stream().mapToLong(r -> nz(r.getIntentCount())).sum();
        long recycledTotal = rows.stream().mapToLong(r -> nz(r.getRecycledCount())).sum();

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("suggestionCount", suggestionTotal);
        total.put("adoptedCount", adoptedTotal);
        total.put("adoptRate", adoptRate(adoptedTotal, suggestionTotal));
        total.put("actionCount", actionTotal);
        total.put("intentCount", intentTotal);
        total.put("intentRate", adoptRate(intentTotal, suggestionTotal));
        total.put("recycledCount", recycledTotal);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", rows);
        data.put("total", total);
        data.put("rateBasis", "推荐条数");
        return data;
    }

    /**
     * 零采纳回流:超期未采纳 + 同会话最近 3 条全未采纳 → 生成待审 FAQ。
     *
     * @param hours 多久之前的推荐才处理(0 表示不限,验证/手动补跑时用)
     * @param limit 单次扫描上限,防止一次拉爆
     */
    public Map<String, Object> recycle(Long workspaceId, int hours, int limit) {
        Date cutoff = new Date(System.currentTimeMillis() - Math.max(0, hours) * 3_600_000L);
        int cap = Math.max(1, Math.min(200, limit));

        List<Long> convIds = conversationMapper.selectList(new LambdaQueryWrapper<CsConversation>()
                .eq(CsConversation::getWorkspaceId, workspaceId)
                .orderByDesc(CsConversation::getId).last("limit 500"))
            .stream().map(CsConversation::getId).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", 0);
        result.put("recycled", 0);
        result.put("created", 0);
        if (convIds.isEmpty()) {
            return result;
        }

        List<CsSeatSuggestion> candidates = suggestionMapper.selectList(
            new LambdaQueryWrapper<CsSeatSuggestion>()
                .in(CsSeatSuggestion::getConversationId, convIds)
                .eq(CsSeatSuggestion::getAdopted, "0")
                .eq(CsSeatSuggestion::getRecycled, "0")
                .isNotNull(CsSeatSuggestion::getTriggerText)
                .lt(CsSeatSuggestion::getCreateTime, cutoff)
                .orderByAsc(CsSeatSuggestion::getId)
                .last("limit " + cap));

        int scanned = 0;
        int recycled = 0;
        int created = 0;
        for (CsSeatSuggestion candidate : candidates) {
            if (candidate.getTriggerText() == null || candidate.getTriggerText().isBlank()) {
                continue;
            }
            scanned++;
            List<CsSeatSuggestion> recent = suggestionMapper.selectList(
                new LambdaQueryWrapper<CsSeatSuggestion>()
                    .eq(CsSeatSuggestion::getConversationId, candidate.getConversationId())
                    .orderByDesc(CsSeatSuggestion::getId)
                    .last("limit " + CONSECUTIVE_WINDOW));
            if (!consecutiveZeroAdopt(recent)) {
                // 会话里还有被采纳的推荐,说明推荐本身有效,先不回流(下轮再看)
                continue;
            }
            if (createPendingAnswer(workspaceId, candidate)) {
                created++;
            }
            markRecycled(candidate);
            recycled++;
        }
        result.put("scanned", scanned);
        result.put("recycled", recycled);
        result.put("created", created);
        log.info("[CS] 零采纳回流完成 workspaceId={} scanned={} recycled={} created={}",
            workspaceId, scanned, recycled, created);
        return result;
    }

    /**
     * 零采纳回流定时任务(P0):每天 2:30 扫一遍所有有渠道的空间,避开 3:00 的留存清理。
     *
     * <p>与手动端点共用同一实现——手动补跑不影响定时,cron 可通过
     * {@code agent.customer-service.assist.recycle-cron} 覆盖,开关见 {@code assist.recycle-enabled}。
     * 单个空间失败只记日志,不中断其余空间。</p>
     */
    @Scheduled(cron = "${agent.customer-service.assist.recycle-cron:0 30 2 * * ?}")
    public void recycleDaily() {
        CsModuleProperties.Assist assist = properties.getAssist();
        if (assist != null && Boolean.FALSE.equals(assist.getRecycleEnabled())) {
            return;
        }
        int hours = assist == null || assist.getRecycleHours() == null ? 24 : assist.getRecycleHours();
        int limit = assist == null || assist.getRecycleLimit() == null ? 50 : assist.getRecycleLimit();
        for (Long workspaceId : activeWorkspaces()) {
            try {
                recycle(workspaceId, hours, limit);
            } catch (Exception e) {
                log.warn("[CS] 定时回流失败 workspaceId={}: {}", workspaceId, e.getMessage());
            }
        }
    }

    /** 有渠道的空间即活动空间——渠道是客服入口,没渠道就没有推荐可回流。 */
    private List<Long> activeWorkspaces() {
        return channelMapper.selectList(new LambdaQueryWrapper<CsChannel>()
                .select(CsChannel::getWorkspaceId)
                .groupBy(CsChannel::getWorkspaceId))
            .stream().map(CsChannel::getWorkspaceId).filter(java.util.Objects::nonNull).toList();
    }

    /** 低质推荐追溯:最近未采纳、带触发原文的推荐,供运营逐条看。 */    public List<CsSeatSuggestion> badcases(Long workspaceId, int limit) {
        List<Long> convIds = conversationMapper.selectList(new LambdaQueryWrapper<CsConversation>()
                .eq(CsConversation::getWorkspaceId, workspaceId)
                .orderByDesc(CsConversation::getId).last("limit 500"))
            .stream().map(CsConversation::getId).toList();
        if (convIds.isEmpty()) {
            return List.of();
        }
        return suggestionMapper.selectList(new LambdaQueryWrapper<CsSeatSuggestion>()
            .in(CsSeatSuggestion::getConversationId, convIds)
            .eq(CsSeatSuggestion::getAdopted, "0")
            .orderByDesc(CsSeatSuggestion::getId)
            .last("limit " + Math.max(1, Math.min(200, limit))));
    }

    /**
     * 连续零采纳判定(纯函数,单测锁定):窗口内条数够 {@link #CONSECUTIVE_WINDOW}
     * 且全部未采纳才算——不足 3 条的会话不判断,避免新会话第一条就被误回流。
     */
    public static boolean consecutiveZeroAdopt(List<CsSeatSuggestion> recent) {
        if (recent == null || recent.size() < CONSECUTIVE_WINDOW) {
            return false;
        }
        for (int i = 0; i < CONSECUTIVE_WINDOW; i++) {
            if ("1".equals(recent.get(i).getAdopted())) {
                return false;
            }
        }
        return true;
    }

    /** 采纳率(百分比,两位小数):按条数算。 */
    public static double adoptRate(long adopted, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(adopted * 10000.0 / total) / 100.0;
    }

    private boolean createPendingAnswer(Long workspaceId, CsSeatSuggestion suggestion) {
        String question = truncate(suggestion.getTriggerText(), QUESTION_LIMIT);
        if (question.isBlank()) {
            return false;
        }
        String hash = CsKnowledgeAnswerService.hash(question);
        boolean exists = answerMapper.selectCount(new LambdaQueryWrapper<CsKnowledgeAnswer>()
            .eq(CsKnowledgeAnswer::getWorkspaceId, workspaceId)
            .eq(CsKnowledgeAnswer::getQuestionHash, hash)) > 0;
        if (exists) {
            // 运营可能已经在补答案或已生效,不覆盖
            return false;
        }
        CsKnowledgeAnswer answer = new CsKnowledgeAnswer();
        answer.setWorkspaceId(workspaceId);
        answer.setQuestion(question);
        answer.setQuestionHash(hash);
        answer.setAnswer("");
        answer.setSource(RECYCLE_SOURCE);
        answer.setSourceRefId(suggestion.getId());
        answer.setStatus("PENDING");
        answer.setCreateTime(new Date());
        answerMapper.insert(answer);
        return true;
    }

    private void markRecycled(CsSeatSuggestion suggestion) {
        suggestionMapper.update(null, new LambdaUpdateWrapper<CsSeatSuggestion>()
            .eq(CsSeatSuggestion::getId, suggestion.getId())
            .set(CsSeatSuggestion::getRecycled, "1"));
    }

    private static long nz(Integer value) {
        return value == null ? 0L : value;
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    /** 当天 00:00 与次日 00:00。 */
    private static Date[] dayRange(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date start = cal.getTime();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        return new Date[]{start, cal.getTime()};
    }
}
