package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.callcenter.common.CsEventEnvelope;
import com.forgeagent.callcenter.common.CsEventTypes;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.customerservice.domain.CsSeatSuggestion;
import com.forgeagent.customerservice.event.CsVisitorMessageSavedEvent;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsSeatSuggestionMapper;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 坐席辅助推荐服务(A1 文本 + A2 语音):输入一句话,异步生成候选话术推给坐席。
 *
 * <p>设计纪律(方案 4.2 节):本服务是访客与语音两条主链路的异步旁路——
 * 文本侧监听 {@link CsVisitorMessageSavedEvent},语音侧由
 * {@code CallEventDispatcher} 在转写落库后调用;任何异常只影响坐席侧体验,
 * 不回传访客链路与语音回调。检索内核与访客机器人同源（FAQ 直取 + CsKnowledgeGateway 混合检索）。</p>
 *
 * <p>协议纪律(方案 4.4 节):SEAT_SUGGEST payload 只增不改,items 上限 3 条,
 * 空候选不落库不推送;A2 起 payload 增加 source(text/voice),前端按来源标注。</p>
 *
 * <p>扩展方向:A3 意图标签（intent 字段已预留）、A4 建议操作（actions 字段已预留）、
 * A5 采纳率指标与 FAQ 回流都挂在本服务上叠加。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class SeatAssistService {

    /** 协议常量:候选上限(方案 4.4 节,调整需同步前端卡片布局)。 */
    public static final int MAX_ITEMS = 3;
    /** 知识分片正文截断长度,推荐卡片只展示摘要级内容。 */
    public static final int CONTENT_SNIPPET = 500;

    /** 语音推荐节流键前缀(A2):按通话维度,不按会话——一通电话内只推一次窗口。 */
    public static final String VOICE_THROTTLE_PREFIX = "cs:assist:voice:";

    /** 不需要给坐席推荐话术的说话人(坐席自己/机器人/系统播报)。 */
    private static final Set<String> NON_CUSTOMER_SPEAKERS =
        Set.of("agent", "seat", "bot", "robot", "system", "ivr");

    /** 消息表里非访客的 senderType(用于挑多轮历史)。 */
    private static final Set<String> NON_VISITOR_SENDERS =
        Set.of("seat", "assistant", "system", "bot", "robot");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CsKnowledgeAnswerService knowledgeAnswerService;
    private final CsKnowledgeGateway knowledgeGateway;
    private final CsSeatSuggestionMapper suggestionMapper;
    private final CsRealtimeTransport transport;
    private final CsChannelMapper channelMapper;
    private final CsConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final CsIntentService intentService;
    private final CsModuleProperties properties;
    /** P1:坐席忽略推荐时记一条 badcase,进入改进流程。 */
    private final CsBadcaseService badcaseService;
    /** M3 后续:图谱辅助检索,把访客口语扩展成领域实体词。 */
    private final CsKgQueryExpander kgQueryExpander;
    /** M4 后续:检索扩展效果指标。 */
    private final CsKgExpandMetricService kgExpandMetricService;

    /**
     * 语音侧输入(A2):ASR 定稿分片的最小描述。
     *
     * <p>刻意只带基本类型而不引用 {@code CsCall}/{@code CsCallSegment}——
     * 模块依赖方向是 callcenter → customer-service,本模块反向引用会形成循环。</p>
     */
    public record VoiceAssistInput(
        Long callId,
        String channelCode,
        Long workspaceId,
        Long conversationId,
        Long ticketId,
        String speaker,
        String text,
        Double confidence,
        boolean finalSegment) {
    }

    /** 文本侧异步旁路入口:推荐失败只记日志,绝不向访客链路回传异常。 */
    @Async
    @EventListener
    public void onVisitorMessageSaved(CsVisitorMessageSavedEvent event) {
        try {
            generate(event.workspaceId(), event.conversationId(), event.ticketId(),
                event.content(), event.knowledgeIds(), "text", null);
        } catch (Exception e) {
            log.warn("[CS] 坐席推荐生成失败 conversationId={}: {}", event.conversationId(), e.getMessage());
        }
    }

    /**
     * 语音侧异步入口(A2):由回调编排器在转写落库后调用。
     *
     * <p>判定顺序:定稿分片 → 说话人 → 置信度 → 会话已绑定 → 节流 → 检索推送。
     * 前四步都是纯内存判断,节流这一步才触达 Redis;任一步不满足都直接返回,
     * 既不加锁也不占线程池。</p>
     */
    @Async
    public void suggestForVoice(VoiceAssistInput input) {
        try {
            if (!acceptsVoice(input.speaker(), input.confidence(), input.finalSegment(),
                minAsrConfidence())) {
                return;
            }
            if (input.conversationId() == null) {
                // 方案第七章:会话绑定前的转写只入库不推荐
                log.debug("[CS] 通话未绑定会话,转写仅入库 callId={}", input.callId());
                return;
            }
            if (!acquireVoiceSlot(input.callId())) {
                log.debug("[CS] 语音推荐节流跳过 callId={} text={}", input.callId(), input.text());
                return;
            }
            generate(input.workspaceId(), input.conversationId(), input.ticketId(), input.text(),
                knowledgeIdsOf(input.channelCode()), "voice", input.callId());
        } catch (Exception e) {
            log.warn("[CS] 语音推荐生成失败 callId={}: {}", input.callId(), e.getMessage());
        }
    }

    /**
     * 语音触发判定(纯函数,A2 验收口径):只有「客户说话的定稿分片 + 置信度达标」才推荐。
     *
     * <p>置信度为 null 视为供应商未上报,放行——拒绝会让不返回该字段的供应商整条链路失效;
     * 说话人为 null 按客户处理,与转写入库的默认口径一致。</p>
     */
    public static boolean acceptsVoice(String speaker, Double confidence, boolean finalSegment,
                                       double minConfidence) {
        if (!finalSegment) {
            return false;
        }
        if (speaker != null && NON_CUSTOMER_SPEAKERS.contains(speaker.trim().toLowerCase())) {
            return false;
        }
        return confidence == null || confidence >= minConfidence;
    }

    /** 最小置信度配置;配置缺失时用 0.6 兜底,避免 NPE 打断链路。 */
    private double minAsrConfidence() {
        CsModuleProperties.Assist assist = properties.getAssist();
        return assist == null || assist.getMinAsrConfidence() == null
            ? 0.6 : assist.getMinAsrConfidence();
    }

    /**
     * 节流占位(Redis trySet):窗口内已有推荐则返回 false。
     * Redis 不可用时降级放行并告警——推荐多推一次,不因缓存故障停掉能力。
     */
    private boolean acquireVoiceSlot(Long callId) {
        CsModuleProperties.Assist assist = properties.getAssist();
        int seconds = assist == null || assist.getThrottleSeconds() == null ? 0 : assist.getThrottleSeconds();
        if (callId == null || seconds <= 0) {
            return true;
        }
        try {
            return RedisUtils.getClient().getBucket(VOICE_THROTTLE_PREFIX + callId)
                .trySet("1", seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[CS] 语音推荐节流失效,降级放行 callId={}: {}", callId, e.getMessage());
            return true;
        }
    }

    /** 语音渠道的知识库范围:与文本渠道同码行,口径与 CsVisitorService 一致。 */
    private List<Long> knowledgeIdsOf(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            return List.of();
        }
        CsChannel channel = channelMapper.selectOne(new LambdaQueryWrapper<CsChannel>()
            .eq(CsChannel::getChannelCode, channelCode).last("limit 1"));
        return channel == null ? List.of() : CsVisitorService.parseIds(channel.getKnowledgeIds());
    }

    /**
     * 两条触发链的公共生成逻辑:检索 → 组装 → 落库 → 推送。
     * 空候选直接返回,不落库不推空卡片。
     *
     * <p>异常由两条链各自的入口捕获(均为异步旁路),此处不吞——吞掉会让
     * 「推荐没生成」既有日志又有异常栈,排查时反而分不清。</p>
     */
    private void generate(Long workspaceId, Long conversationId, Long ticketId, String text,
                          List<Long> knowledgeIds, String source, Long callId) throws Exception {
        if (conversationId == null || text == null || text.isBlank()) {
            return;
        }
        // A3:先打标再检索——意图决定「并入哪些知识库」与「结果怎么重排」;未命中不硬凑,照常推荐
        CsIntentService.Match intent = intentService.classify(workspaceId, text,
            recentVisitorTexts(conversationId));
        // M3 后续:图谱扩展只进检索 query,FAQ 确定性匹配与触发原文不变
        CsKgQueryExpander.Expansion expansion = kgQueryExpander.expandDetailed(workspaceId, text);
        List<KnowledgeSearchHitVo> hits = CsIntentService.reweight(
            knowledgeGateway.search(workspaceId, mergeKnowledgeIds(knowledgeIds, intent),
                expansion.query()), intent);
        // M4 后续:检索扩展效果埋点
        kgExpandMetricService.record(workspaceId, !expansion.terms().isEmpty(), hits.isEmpty());
        List<Map<String, Object>> items = buildItems(
            knowledgeAnswerService.match(workspaceId, text), hits);
        if (items.isEmpty()) {
            return;
        }
        CsSeatSuggestion record = new CsSeatSuggestion();
        record.setConversationId(conversationId);
        record.setTicketId(ticketId);
        record.setItems(MAPPER.writeValueAsString(items));
        // A4 动作随推荐一起快照:执行时按 code 在快照里匹配,只决定「给不给按钮」,
        // 工单状态与受理人由执行服务再校验一次
        List<Map<String, Object>> actions = SeatAssistActions.build(items, ticketId != null);
        record.setActions(MAPPER.writeValueAsString(actions));
        record.setSource(source);
        record.setCallId(callId);
        // A5:留触发原文,零采纳时作为待审 FAQ 的问题(截断与列宽一致)
        record.setTriggerText(truncate(text, 500));
        record.setRecycled("0");
        record.setIntent(intent == null ? null : intent.code());
        record.setTopScore(((Number) items.get(0).get("score")).doubleValue());
        record.setAdopted("0");
        record.setCreateTime(new Date());
        suggestionMapper.insert(record);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suggestionId", record.getId());
        payload.put("items", items);
        payload.put("actions", actions);
        payload.put("source", source);
        payload.put("intent", intent == null ? null : intent.code());
        payload.put("intentName", intent == null ? null : intent.name());
        transport.push(CsEventEnvelope.of(CsEventTypes.SEAT_SUGGEST, conversationId,
            ticketId, payload));
        log.info("[CS] 坐席推荐已生成 conversationId={} source={} intent={} items={} actions={} suggestionId={}",
            conversationId, source, intent == null ? "-" : intent.code(), items.size(), actions.size(),
            record.getId());
    }

    /** 意图关联的知识库并入渠道范围(并集去重):客户问退货时,退货知识库优先参与检索。 */
    static List<Long> mergeKnowledgeIds(List<Long> channelIds, CsIntentService.Match intent) {
        if (intent == null || intent.kbIds() == null || intent.kbIds().isEmpty()) {
            return channelIds;
        }
        if (channelIds == null || channelIds.isEmpty()) {
            return intent.kbIds();
        }
        LinkedHashSet<Long> merged = new LinkedHashSet<>(channelIds);
        merged.addAll(intent.kbIds());
        return new ArrayList<>(merged);
    }

    /**
     * 最近几轮访客原话(最多 {@link CsIntentService#HISTORY_TURNS} 条,按时间正序)。
     * 只用于意图兜底,查不到就是空列表——不因历史缺失影响推荐主流程。
     */
    private List<String> recentVisitorTexts(Long conversationId) {
        try {
            CsConversation conv = conversationMapper.selectById(conversationId);
            if (conv == null || conv.getAgentSessionId() == null) {
                return List.of();
            }
            // 宽取近 8 条再在内存里挑访客原话:消息表的 role/senderType 由对话侧写入,
            // 两种口径都要兼容,靠 SQL 精确过滤容易因口径差异整段查空
            List<AgentMessage> rows = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, conv.getAgentSessionId())
                .orderByDesc(AgentMessage::getId)
                .last("limit 8"));
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            Collections.reverse(rows);
            List<String> texts = new ArrayList<>();
            for (AgentMessage row : rows) {
                if (isVisitorMessage(row) && row.getContent() != null && !row.getContent().isBlank()) {
                    texts.add(row.getContent());
                }
            }
            int from = Math.max(0, texts.size() - CsIntentService.HISTORY_TURNS);
            return texts.subList(from, texts.size());
        } catch (Exception e) {
            log.warn("[CS] 多轮历史读取失败 conversationId={}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    /** 访客原话判定:坐席/助手/系统/机器人说的话不能用来推断客户意图。 */
    private static boolean isVisitorMessage(AgentMessage row) {
        String sender = row.getSenderType();
        if (sender != null && !sender.isBlank()) {
            return !NON_VISITOR_SENDERS.contains(sender.toLowerCase());
        }
        return row.getRole() == null || "user".equalsIgnoreCase(row.getRole());
    }

    /**
     * 纯函数组装候选:FAQ 优先(确定性答案),知识分片按检索分排序、正文截断;
     * 超过 top3 丢弃,无候选返回空列表(不推空卡片)。
     */
    public static List<Map<String, Object>> buildItems(CsKnowledgeAnswer faq, List<KnowledgeSearchHitVo> hits) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (faq != null) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "faq");
            item.put("title", truncate(faq.getQuestion(), 80));
            item.put("content", faq.getAnswer());
            item.put("score", 1.00);
            item.put("sourceId", String.valueOf(faq.getId()));
            items.add(item);
        }
        if (hits != null) {
            for (KnowledgeSearchHitVo hit : hits) {
                if (items.size() >= MAX_ITEMS) {
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "kb");
                item.put("title", truncate(hit.getFileName(), 80));
                item.put("content", truncate(hit.getContent(), CONTENT_SNIPPET));
                item.put("score", hit.getScore() == null ? 0.0 : Math.round(hit.getScore() * 100) / 100.0);
                item.put("sourceId", hit.getChunkId() == null ? null : String.valueOf(hit.getChunkId()));
                items.add(item);
            }
        }
        return items;
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /** 反馈:采纳方式 send/copy/ignore;仅允许从未采纳状态变更一次。 */
    public boolean feedback(Long suggestionId, String action, Long workspaceId) {
        int rows = suggestionMapper.update(null, new LambdaUpdateWrapper<CsSeatSuggestion>()
            .eq(CsSeatSuggestion::getId, suggestionId)
            .eq(CsSeatSuggestion::getAdopted, "0")
            .set(CsSeatSuggestion::getAdopted, "1")
            .set(CsSeatSuggestion::getAdoptAction, action));
        // P1:坐席明确"不再推荐"是最强的负反馈信号,直接进 badcase
        if (rows > 0 && "ignore".equals(action)) {
            badcaseService.recordSeatIgnore(suggestionId, workspaceId);
        }
        return rows > 0;
    }

    /** 坐席侧拉取:按会话取最新推荐,workspace 隔离由调用方(conversation 归属)保证。 */
    public List<CsSeatSuggestion> listByConversation(Long conversationId) {
        return suggestionMapper.selectList(new LambdaQueryWrapper<CsSeatSuggestion>()
            .eq(CsSeatSuggestion::getConversationId, conversationId)
            .orderByDesc(CsSeatSuggestion::getId).last("limit 5"));
    }
}
