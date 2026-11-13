package com.forgeagent.callcenter.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.common.CsEventEnvelope;
import com.forgeagent.callcenter.common.CsEventTypes;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallInsight;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.engine.ChannelControl;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.engine.ivr.IntentionClassifier;
import com.forgeagent.callcenter.mapper.CsCallInsightMapper;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通话洞察管道(C4,方案 docs/07 §三 P0-2):把转写分片喂成结构化洞察。
 *
 * <p><b>执行纪律(旁路,不影响通话主链路)</b>:</p>
 * <ul>
 *   <li>独立有界线程池 {@code cc-insight-*};队列满即拒绝并 WARN 计数,回调线程永不被阻塞;</li>
 *   <li>每次执行挂看门狗硬超时,到点 interrupt 工作线程——毒分片不会卡死管道;</li>
 *   <li>任何失败都只写一行 {@code label=ERROR} 并<b>空转游标</b>(游标前移),
 *       同一个分片不会被反复重试;错误码进 payload 供人工复核;</li>
 *   <li>幂等靠表唯一键 {@code (call_id, kind, item_key)}:重放只更新不追加,
 *       游标只增不减(乱序回放的旧分片直接跳过)。</li>
 * </ul>
 *
 * <p><b>三类执行器</b>:情绪(通话中、按轮次节流、只评主叫终稿)/ 意图(挂机后全文,
 * 走 {@link IntentionClassifier} 双引擎 SPI)/ 信息提取(挂机后按渠道声明字段)。
 * 质检报告同属挂机后执行,聚合本通电话的情绪分布与提取结果。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class InsightPipeline {

    /** 意图洞察的固定实例键(一通电话一个)。 */
    public static final String ITEM_INTENT = "final";
    /** 质检报告的固定实例键。 */
    public static final String ITEM_QA = "default";
    /** 失败行标签。 */
    public static final String LABEL_ERROR = "ERROR";
    /** 意图未命中的落库标签。 */
    public static final String LABEL_UNKNOWN = "UNKNOWN";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CallcenterProperties properties;
    private final CsCallInsightMapper insightMapper;
    private final CsCallSegmentMapper segmentMapper;
    private final ChannelVoiceSettings channelSettings;
    private final LiveChannelRegistry liveChannels;
    private final ObjectProvider<SentimentAnalyzer> sentimentAnalyzerProvider;
    private final ObjectProvider<IntentionClassifier> intentionClassifierProvider;
    /** SSE 通道属文本域 Bean,客服开关关闭时为空,情绪上行静默跳过。 */
    private final ObjectProvider<CsRealtimeTransport> transportProvider;

    /** 每通话上次情绪评估时间(节流用);挂机后清理。 */
    private final Map<Long, Long> lastSentimentAt = new ConcurrentHashMap<>();
    /** 每通话负面自动转接是否已触发(防抖:同通话只触发一次)。 */
    private final Map<Long, Boolean> autoTransferFired = new ConcurrentHashMap<>();
    /** 被拒绝的任务计数(可观测性:队列长期满说明池子配小了)。 */
    private final AtomicLong rejected = new AtomicLong();

    private volatile ThreadPoolExecutor pool;
    private volatile ScheduledExecutorService watchdog;
    private final Object startLock = new Object();

    // ------------------------------------------------------------------
    // 挂点
    // ------------------------------------------------------------------

    /**
     * 分片入库后调用(通话中):只处理主叫终稿,受节流约束。
     * 任何判定不通过都直接返回,不产生任务。
     */
    public void onSegment(CsCall call, CsCallSegment segment) {
        if (!enabled() || call == null || call.getId() == null || segment == null
            || segment.getId() == null || segment.getText() == null
            || segment.getText().isBlank()) {
            return;
        }
        if (!"caller".equalsIgnoreCase(segment.getSpeaker()) || !"1".equals(segment.getIsFinal())) {
            return;
        }
        if (!throttleAllows(call.getId())) {
            return;
        }
        final String text = segment.getText();
        final long cursor = segment.getId();
        submit(InsightKind.SENTIMENT, () -> runSentiment(call, cursor, text));
    }

    /**
     * 挂机后调用:意图 + 信息提取 + 质检报告三类一起跑(一个任务内顺序执行,
     * 避免同一通话三个任务并发抢同一批分片)。
     */
    public void onCallEnded(CsCall call) {
        if (!enabled() || call == null || call.getId() == null) {
            return;
        }
        submit(null, () -> runPostCall(call));
    }

    // ------------------------------------------------------------------
    // 执行体
    // ------------------------------------------------------------------

    private void runSentiment(CsCall call, long cursor, String text) {
        String itemKey = "turn-" + cursor;
        SentimentAnalyzer analyzer = sentimentAnalyzerProvider.getIfAvailable();
        if (analyzer == null) {
            recordError(call, InsightKind.SENTIMENT, itemKey, cursor, "NO_ANALYZER");
            return;
        }
        SentimentAnalyzer.Result result;
        try {
            result = analyzer.analyze(text);
        } catch (Exception e) {
            recordError(call, InsightKind.SENTIMENT, itemKey, cursor, "ANALYZE_FAILED");
            log.warn("[CC] 情绪分析失败 callId={}: {}", call.getCallId(), e.getMessage());
            return;
        }
        if (result == null) {
            recordError(call, InsightKind.SENTIMENT, itemKey, cursor, "EMPTY_RESULT");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engine", analyzer.code());
        payload.put("textLen", text.length());
        String transfer = decideAutoTransfer(call, result);
        if (transfer != null) {
            payload.put("autoTransfer", transfer);
        }
        record(call, InsightKind.SENTIMENT, itemKey, cursor, result.label(),
            BigDecimal.valueOf(result.score()), toJson(payload));
        pushSentiment(call, result, transfer);
    }

    private void runPostCall(CsCall call) {
        List<CsCallSegment> segments = loadSegments(call);
        long cursor = segments.stream().mapToLong(s -> s.getId() == null ? 0L : s.getId()).max().orElse(0L);
        String callerText = joinText(segments, "caller");
        String fullText = joinText(segments, null);

        runIntent(call, cursor, callerText, segments);
        runExtract(call, cursor, fullText);
        runQaReport(call, cursor, segments, fullText);

        // 通话结束清理每通话状态,避免长驻引用
        lastSentimentAt.remove(call.getId());
        autoTransferFired.remove(call.getId());
    }

    /** 意图:全文 + 最近 3 句上下文,走 SPI(正则引擎 / 未来 LLM 引擎同口)。 */
    private void runIntent(CsCall call, long cursor, String callerText, List<CsCallSegment> segments) {
        IntentionClassifier classifier = intentionClassifierProvider.getIfAvailable();
        if (classifier == null) {
            recordError(call, InsightKind.INTENT, ITEM_INTENT, cursor, "NO_CLASSIFIER");
            return;
        }
        if (callerText.isBlank()) {
            record(call, InsightKind.INTENT, ITEM_INTENT, cursor, LABEL_UNKNOWN,
                BigDecimal.ZERO, toJson(Map.of("reason", "EMPTY_TRANSCRIPT")));
            return;
        }
        try {
            IntentionClassifier.Result result = classifier.classify(
                call.getWorkspaceId(), callerText, recentTexts(segments, 3));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("engine", classifier.getClass().getSimpleName());
            payload.put("textLen", callerText.length());
            payload.put("segmentCount", segments.size());
            if (result == null || result.intentCode() == null || result.intentCode().isBlank()) {
                payload.put("reason", "NO_MATCH");
                record(call, InsightKind.INTENT, ITEM_INTENT, cursor, LABEL_UNKNOWN,
                    BigDecimal.ZERO, toJson(payload));
                return;
            }
            record(call, InsightKind.INTENT, ITEM_INTENT, cursor, result.intentCode(),
                BigDecimal.valueOf(result.confidence()), toJson(payload));
        } catch (Exception e) {
            recordError(call, InsightKind.INTENT, ITEM_INTENT, cursor, "CLASSIFY_FAILED");
            log.warn("[CC] 意图归类失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
    }

    /** 信息提取:字段清单来自渠道配置,规则内置,命中一条写一行(便于按字段查询)。 */
    private void runExtract(CsCall call, long cursor, String fullText) {
        ChannelVoiceSettings.Settings settings = channelSettings.of(call.getChannelCode());
        List<String> keys = settings.extractKeys();
        if (keys.isEmpty()) {
            return;
        }
        try {
            List<ExtractRuleEngine.Hit> hits = ExtractRuleEngine.extract(fullText, keys);
            for (ExtractRuleEngine.Hit hit : hits) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("value", hit.value());
                payload.put("declared", keys);
                record(call, InsightKind.EXTRACT, hit.key(), cursor, hit.key(),
                    null, toJson(payload));
            }
        } catch (Exception e) {
            recordError(call, InsightKind.EXTRACT, "all", cursor, "EXTRACT_FAILED");
            log.warn("[CC] 信息提取失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
    }

    /** 质检报告:本通电话的转写规模 + 情绪分布 + 提取/意图结果摘要。 */
    private void runQaReport(CsCall call, long cursor, List<CsCallSegment> segments, String fullText) {
        try {
            Map<String, Integer> distribution = sentimentDistribution(call);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("segmentCount", segments.size());
            payload.put("callerTurns", segments.stream()
                .filter(s -> "caller".equalsIgnoreCase(s.getSpeaker())).count());
            payload.put("botTurns", segments.stream()
                .filter(s -> "bot".equalsIgnoreCase(s.getSpeaker())).count());
            payload.put("sentiment", distribution);
            payload.put("textLen", fullText.length());
            // 挂机瞬间 call.durationSec 还没被 CDR 回填(CDR 事件晚于 hangup 到达),
            // 为空或 0 时用分片时间戳兜底,避免报告里恒为 0
            payload.put("durationSec", durationSec(call, segments));
            payload.put("hangupCause", call.getHangupCause());
            record(call, InsightKind.QA_REPORT, ITEM_QA, cursor, "REPORT",
                null, toJson(payload));
        } catch (Exception e) {
            recordError(call, InsightKind.QA_REPORT, ITEM_QA, cursor, "REPORT_FAILED");
            log.warn("[CC] 质检报告生成失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 自动转接(方案 v2 解除的边界:默认关)
    // ------------------------------------------------------------------

    /**
     * 负面情绪自动转接判定与执行;返回非 null 表示已决策(payload 里留痕)。
     *
     * <p>四道闸门:开关打开 → 渠道配了队列 → 分值越阈值 → 本通话未触发过。
     * 有在线通道才真正下发 {@code uuid_transfer};AICC 回调链路没有媒体面时
     * 只记录 {@code PENDING_NO_CHANNEL},交给坐席辅助人工兜底。</p>
     */
    private String decideAutoTransfer(CsCall call, SentimentAnalyzer.Result result) {
        if (!Boolean.TRUE.equals(properties.getAutoTransferOnNegative())) {
            return null;
        }
        String queue = channelSettings.of(call.getChannelCode()).autoTransferQueue();
        if (queue == null || queue.isBlank()) {
            return null;
        }
        double threshold = properties.getNegativeScoreThreshold() == null
            ? -0.35 : properties.getNegativeScoreThreshold();
        if (result.score() > threshold) {
            return null;
        }
        if (autoTransferFired.putIfAbsent(call.getId(), Boolean.TRUE) != null) {
            return "SKIPPED_DUPLICATE";
        }
        ChannelControl channel = liveChannels.find(call.getCallId());
        if (channel == null) {
            log.warn("[CC] 负面自动转接仅留痕:无在线通道 callId={} queue={}", call.getCallId(), queue);
            return "PENDING_NO_CHANNEL";
        }
        try {
            channel.transferToQueue(queue);
            log.info("[CC] 负面自动转接已下发 callId={} queue={} score={}",
                call.getCallId(), queue, result.score());
            return "FIRED";
        } catch (Exception e) {
            log.warn("[CC] 负面自动转接下发失败 callId={}: {}", call.getCallId(), e.getMessage());
            return "FAILED";
        }
    }

    /** 负面情绪上行坐席弹屏(会话未绑定或无 SSE 通道时静默跳过)。 */
    private void pushSentiment(CsCall call, SentimentAnalyzer.Result result, String transfer) {
        if (!SentimentAnalyzer.Labels.NEGATIVE.equals(result.label()) || call.getConversationId() == null) {
            return;
        }
        CsRealtimeTransport transport = transportProvider.getIfAvailable();
        if (transport == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", call.getCallId());
        payload.put("label", result.label());
        payload.put("score", result.score());
        if (transfer != null) {
            payload.put("autoTransfer", transfer);
        }
        try {
            transport.push(CsEventEnvelope.of(CsEventTypes.CALL_SENTIMENT,
                call.getConversationId(), call.getTicketId(), payload));
        } catch (Exception e) {
            log.debug("[CC] 情绪事件上行失败 callId={}: {}", call.getCallId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 落库(幂等 + 游标单调)
    // ------------------------------------------------------------------

    /**
     * 写一行洞察。语义:
     * <ul>
     *   <li>行不存在 → 插入;并发插入撞唯一键 → 回查后转更新;</li>
     *   <li>行存在且新游标 &lt; 已有游标 → 视为乱序旧回放,整行跳过(游标只增不减);</li>
     *   <li>行存在且新游标 ≥ 已有游标 → 覆盖内容,游标取较大值。</li>
     * </ul>
     */
    private void record(CsCall call, InsightKind kind, String itemKey, long cursor,
                        String label, BigDecimal score, String payload) {
        Date now = new Date();
        try {
            CsCallInsight existing = find(call.getId(), kind, itemKey);
            if (existing == null) {
                CsCallInsight row = newRow(call, kind, itemKey, cursor, label, score, payload, now);
                try {
                    insightMapper.insert(row);
                } catch (DuplicateKeyException e) {
                    // 并发写同一实例:另一路径已插入,改走更新(唯一键是最终保证)
                    existing = find(call.getId(), kind, itemKey);
                    if (existing == null) {
                        throw e;
                    }
                    updateExisting(existing, cursor, label, score, payload, now);
                }
                return;
            }
            updateExisting(existing, cursor, label, score, payload, now);
        } catch (Exception e) {
            // 洞察写库失败不能反噬主链路:只 WARN,不重试
            log.warn("[CC] 洞察落库失败 callId={} kind={} item={}: {}",
                call.getCallId(), kind, itemKey, e.getMessage());
        }
    }

    private void updateExisting(CsCallInsight existing, long cursor, String label,
                                BigDecimal score, String payload, Date now) {
        long current = existing.getSegmentUntilSeq() == null ? 0L : existing.getSegmentUntilSeq();
        if (cursor < current) {
            log.debug("[CC] 洞察游标回退,跳过旧回放 callId={} kind={} item={} cursor={} current={}",
                existing.getCallId(), existing.getKind(), existing.getItemKey(), cursor, current);
            return;
        }
        existing.setSegmentUntilSeq(Math.max(cursor, current));
        existing.setLabel(label);
        existing.setScore(score);
        existing.setPayload(payload);
        existing.setUpdateTime(now);
        insightMapper.updateById(existing);
    }

    private CsCallInsight newRow(CsCall call, InsightKind kind, String itemKey, long cursor,
                                 String label, BigDecimal score, String payload, Date now) {
        CsCallInsight row = new CsCallInsight();
        row.setCallId(call.getId());
        row.setWorkspaceId(call.getWorkspaceId());
        row.setKind(kind.name());
        row.setItemKey(itemKey);
        row.setSegmentUntilSeq(cursor);
        row.setLabel(label);
        row.setScore(score);
        row.setPayload(payload);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }

    private CsCallInsight find(Long callId, InsightKind kind, String itemKey) {
        return insightMapper.selectByKey(callId, kind.name(), itemKey);
    }

    /** 失败行:标签 ERROR + 错误码进 payload,游标照常前移(空转,不再重试同分片)。 */
    private void recordError(CsCall call, InsightKind kind, String itemKey, long cursor, String code) {
        record(call, kind, itemKey, cursor, LABEL_ERROR, null,
            toJson(Map.of("error", code)));
    }

    // ------------------------------------------------------------------
    // 线程池
    // ------------------------------------------------------------------

    /**
     * 提交任务;队列满/池已关闭时只 WARN 计数并丢弃——
     * 调用方是回调线程,任何阻塞都会拖慢语音链路。
     */
    private void submit(InsightKind kind, Runnable task) {
        try {
            ensureStarted().execute(() -> runGuarded(kind, task));
        } catch (RejectedExecutionException e) {
            long total = rejected.incrementAndGet();
            if (total % 50 == 1) {
                log.warn("[CC] 洞察任务被拒绝(队列满或已关闭)累计={} kind={}", total, kind);
            }
        } catch (Exception e) {
            log.warn("[CC] 洞察任务提交失败: {}", e.getMessage());
        }
    }

    /** 看门狗硬超时:到点 interrupt 工作线程,保证毒分片不占死线程。 */
    private void runGuarded(InsightKind kind, Runnable task) {
        long timeoutMs = properties.getInsightTimeoutMs() == null ? 3000L : properties.getInsightTimeoutMs();
        AtomicBoolean killed = new AtomicBoolean(false);
        Thread worker = Thread.currentThread();
        ScheduledFuture<?> killer = null;
        try {
            killer = watchdog.schedule(() -> {
                killed.set(true);
                worker.interrupt();
            }, timeoutMs, TimeUnit.MILLISECONDS);
            task.run();
        } catch (Exception e) {
            if (killed.get()) {
                log.warn("[CC] 洞察任务硬超时({}ms) kind={}", timeoutMs, kind);
            } else {
                log.warn("[CC] 洞察任务异常 kind={}: {}", kind, e.getMessage());
            }
        } finally {
            if (killer != null) {
                killer.cancel(false);
            }
            // 清除可能残留的中断位:线程池复用线程,脏中断位会让下一个任务立刻失败
            Thread.interrupted();
        }
    }

    private ThreadPoolExecutor ensureStarted() {
        ThreadPoolExecutor current = pool;
        if (current != null) {
            return current;
        }
        synchronized (startLock) {
            if (pool == null) {
                int size = properties.getInsightPoolSize() == null ? 2 : Math.max(1, properties.getInsightPoolSize());
                int capacity = properties.getInsightQueueCapacity() == null
                    ? 512 : Math.max(1, properties.getInsightQueueCapacity());
                AtomicInteger seq = new AtomicInteger();
                pool = new ThreadPoolExecutor(size, size, 60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(capacity),
                    runnable -> {
                        Thread thread = new Thread(runnable, "cc-insight-" + seq.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
                ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(runnable, "cc-insight-watchdog");
                    thread.setDaemon(true);
                    return thread;
                });
                timer.setRemoveOnCancelPolicy(true);
                watchdog = timer;
                log.info("[CC] 洞察线程池启动 pool={} queue={}", size, capacity);
            }
            return pool;
        }
    }

    /** 容器启动即建池(便于运维一眼看到线程池装配日志);失败不影响启动。 */
    @PostConstruct
    void init() {
        if (enabled()) {
            ensureStarted();
        }
    }

    @PreDestroy
    void shutdown() {
        ThreadPoolExecutor current = pool;
        if (current != null) {
            current.shutdownNow();
        }
        ScheduledExecutorService timer = watchdog;
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private boolean enabled() {
        return Boolean.TRUE.equals(properties.getInsightEnabled());
    }

    /** 节流:同一通话两次情绪评估间隔不小于配置值(原子判定,避免并发双跑)。 */
    boolean throttleAllows(Long callId) {
        long interval = properties.getSentimentMinIntervalMs() == null
            ? 0L : Math.max(0L, properties.getSentimentMinIntervalMs());
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean(false);
        lastSentimentAt.compute(callId, (key, previous) -> {
            if (previous != null && now - previous < interval) {
                return previous;
            }
            allowed.set(true);
            return now;
        });
        return allowed.get();
    }

    /** 当前被拒绝任务数(单测与运维观测)。 */
    public long rejectedCount() {
        return rejected.get();
    }

    private List<CsCallSegment> loadSegments(CsCall call) {
        try {
            List<CsCallSegment> rows = segmentMapper.selectList(new LambdaQueryWrapper<CsCallSegment>()
                .eq(CsCallSegment::getCallId, call.getId())
                .orderByAsc(CsCallSegment::getId));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("[CC] 转写分片读取失败 callId={}: {}", call.getCallId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 通话时长(秒):优先用 call 上的权威字段(CDR 已回填时),
     * 否则用分片最大 endMs 兜底——挂机瞬间 CDR 通常还没到。
     */
    static int durationSec(CsCall call, List<CsCallSegment> segments) {
        Integer authoritative = call.getDurationSec();
        if (authoritative != null && authoritative > 0) {
            return authoritative;
        }
        long maxEndMs = 0L;
        for (CsCallSegment segment : segments) {
            if (segment.getEndMs() != null && segment.getEndMs() > maxEndMs) {
                maxEndMs = segment.getEndMs();
            }
        }
        return (int) (maxEndMs / 1000L);
    }

    /** 按说话人拼接文本;speaker 为 null 表示全量。 */
    static String joinText(List<CsCallSegment> segments, String speaker) {
        StringBuilder sb = new StringBuilder();
        for (CsCallSegment segment : segments) {
            if (speaker != null && !speaker.equalsIgnoreCase(segment.getSpeaker())) {
                continue;
            }
            if (segment.getText() == null || segment.getText().isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(segment.getText());
        }
        return sb.toString();
    }

    /** 取最后 limit 句主叫文本(意图上下文;不足则全给)。 */    static List<String> recentTexts(List<CsCallSegment> segments, int limit) {
        List<String> texts = new ArrayList<>();
        for (CsCallSegment segment : segments) {
            if (!"caller".equalsIgnoreCase(segment.getSpeaker())) {
                continue;
            }
            if (segment.getText() == null || segment.getText().isBlank()) {
                continue;
            }
            texts.add(segment.getText());
        }
        return texts.size() <= limit ? texts : texts.subList(texts.size() - limit, texts.size());
    }

    private Map<String, Integer> sentimentDistribution(CsCall call) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        try {
            List<CsCallInsight> rows = insightMapper.selectList(new LambdaQueryWrapper<CsCallInsight>()
                .eq(CsCallInsight::getCallId, call.getId())
                .eq(CsCallInsight::getKind, InsightKind.SENTIMENT.name()));
            for (CsCallInsight row : rows) {
                String label = row.getLabel() == null ? LABEL_ERROR : row.getLabel();
                distribution.merge(label, 1, Integer::sum);
            }
        } catch (Exception e) {
            log.debug("[CC] 情绪分布聚合降级 callId={}: {}", call.getCallId(), e.getMessage());
        }
        return distribution;
    }

    private static String toJson(Map<String, ?> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
