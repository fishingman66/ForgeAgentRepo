package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsHandoffSummary;
import com.forgeagent.customerservice.domain.CsSeatSuggestion;
import com.forgeagent.customerservice.event.CsHandoffRequestedEvent;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsHandoffSummaryMapper;
import com.forgeagent.customerservice.mapper.CsSeatSuggestionMapper;
import com.forgeagent.rag.domain.AgentKbZeroHitLog;
import com.forgeagent.rag.mapper.AgentKbZeroHitLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话交接摘要(P0,差距分析 4.5)。
 *
 * <p>坐席接手工单时最痛的是「往上翻几十条消息才知道客户要什么」。这里在转人工建单后
 * 异步生成一份结构化摘要:一句话概括 / 意图 / 情绪 / 已尝试 / 待解决 / 下一步 / 建议开场。</p>
 *
 * <p><b>当前是规则版</b>:意图取 A3 的推荐标签,待解决问题取零命中日志,已尝试看候选里有没有
 * FAQ 命中,开场话术取推荐首条。不调模型——平台没有「一次性短调用」的现成入口,
 * 为摘要在客服域新引一套模型调用不划算;LLM 版留到 P1,替换点就是 {@link #generate}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsHandoffSummaryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 情绪判定只看最近几轮,再往前的话题多半已翻篇。 */
    private static final int EMOTION_TURNS = 5;
    /** 负向词:命中 2 次及以上判「愤怒」,1 次判「不满」。 */
    private static final Set<String> NEGATIVE = Set.of(
        "投诉", "差评", "太差", "不满意", "垃圾", "生气", "气死", "骗", "举报", "态度", "恶心", "坑");

    private final CsHandoffSummaryMapper summaryMapper;
    private final CsSeatSuggestionMapper suggestionMapper;
    private final CsConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentKbZeroHitLogMapper zeroHitMapper;
    /** P2 收尾:失败进死信。 */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 转人工建单后异步生成;失败只记日志与死信,不影响坐席接单。 */
    @Async
    @EventListener
    public void onHandoffRequested(CsHandoffRequestedEvent event) {
        try {
            generate(event.workspaceId(), event.conversationId(), event.ticketId());
        } catch (Exception e) {
            log.warn("[CS] 交接摘要生成失败 conversationId={}: {}", event.conversationId(), e.getMessage());
            // P2 收尾:失败进死信,运营可重投(重投 = 重新生成)
            eventPublisher.publishEvent(new com.forgeagent.customerservice.event.CsBusinessFailureEvent(
                event.workspaceId(), null, "HANDOFF_SUMMARY",
                event.conversationId(), event.ticketId(), e.getMessage()));
        }
    }

    /** 生成并落库(同一会话覆盖写)。 */
    public Map<String, Object> generate(Long workspaceId, Long conversationId, Long ticketId) throws Exception {
        CsConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            throw new ServiceException("会话不存在");
        }
        List<String> texts = recentVisitorTexts(conv);
        List<CsSeatSuggestion> recent = suggestionMapper.selectList(
            new LambdaQueryWrapper<CsSeatSuggestion>()
                .eq(CsSeatSuggestion::getConversationId, conversationId)
                .orderByDesc(CsSeatSuggestion::getId).last("limit 3"));
        String intent = recent.stream().map(CsSeatSuggestion::getIntent)
            .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        String opening = firstItemContent(recent);
        List<String> pending = pendingQuestions(conv);
        String emotion = judgeEmotion(texts);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("oneLineSummary", oneLine(intent, emotion, texts.size(), pending.size()));
        summary.put("userIntent", intent);
        summary.put("emotion", emotion);
        summary.put("triedSolutions", triedSolutions(recent, pending));
        summary.put("pendingIssues", pending);
        summary.put("suggestedNextStep", nextStep(intent, pending));
        summary.put("suggestedReply", opening);
        summary.put("turns", texts.size());
        // 没有候选话术说明没跑过检索(关键词/敏感词直接转人工),此时摘要只有规则结论
        summary.put("degraded", opening == null);
        upsert(workspaceId, conversationId, ticketId, summary, texts.size());
        return summary;
    }

    /** 坐席侧读取:按工单取最新一条,没有返回空对象(前端据此不渲染卡片)。 */
    public Map<String, Object> latest(Long workspaceId, Long ticketId) {
        CsHandoffSummary row = summaryMapper.selectOne(new LambdaQueryWrapper<CsHandoffSummary>()
            .eq(CsHandoffSummary::getWorkspaceId, workspaceId)
            .eq(CsHandoffSummary::getTicketId, ticketId)
            .orderByDesc(CsHandoffSummary::getId).last("limit 1"));
        if (row == null || row.getSummaryJson() == null || row.getSummaryJson().isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(row.getSummaryJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("[CS] 交接摘要解析失败 ticketId={}: {}", ticketId, e.getMessage());
            return Map.of();
        }
    }

    /** 情绪判定(纯函数):负向命中多判愤怒,单次判不满,其余按平静。 */
    public static String judgeEmotion(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return "未知";
        }
        int negative = 0;
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (String keyword : NEGATIVE) {
                if (text.contains(keyword)) {
                    negative++;
                    break;
                }
            }
        }
        if (negative >= 2) {
            return "愤怒";
        }
        if (negative == 1) {
            return "不满";
        }
        return "平静";
    }

    private String oneLine(String intent, String emotion, int turns, int pending) {
        StringBuilder sb = new StringBuilder("客户");
        sb.append(intent == null ? "咨询" : "咨询「" + intent + "」");
        sb.append("，").append(turns).append(" 轮对话，情绪").append(emotion);
        if (pending > 0) {
            sb.append("，仍有 ").append(pending).append(" 个问题未得到答案");
        }
        return sb.toString();
    }

    private List<String> triedSolutions(List<CsSeatSuggestion> recent, List<String> pending) {
        List<String> tried = new ArrayList<>();
        boolean faqHit = recent.stream().anyMatch(row -> row.getItems() != null && row.getItems().contains("\"type\":\"faq\""));
        if (faqHit) {
            tried.add("已按标准 FAQ 回答了一次");
        }
        if (!pending.isEmpty()) {
            tried.add("知识库检索未命中 " + pending.size() + " 次");
        }
        if (tried.isEmpty()) {
            tried.add("暂无");
        }
        return tried;
    }

    private String nextStep(String intent, List<String> pending) {
        if (!pending.isEmpty()) {
            return "优先人工回答待解决问题，必要时补充进 FAQ：" + pending.get(0);
        }
        if (intent == null) {
            return "先确认客户具体诉求，再核对知识库与订单信息";
        }
        return "按「" + intent + "」类目核对政策后再答复，未核实前不承诺结果";
    }

    private String firstItemContent(List<CsSeatSuggestion> recent) {
        for (CsSeatSuggestion row : recent) {
            if (row.getItems() == null || row.getItems().isBlank()) {
                continue;
            }
            try {
                List<Map<String, Object>> items = MAPPER.readValue(row.getItems(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
                for (Map<String, Object> item : items) {
                    Object content = item.get("content");
                    if (content != null && !String.valueOf(content).isBlank()) {
                        return String.valueOf(content);
                    }
                }
            } catch (Exception e) {
                log.debug("[CS] 候选快照解析失败 suggestionId={}: {}", row.getId(), e.getMessage());
            }
        }
        return null;
    }

    private List<String> pendingQuestions(CsConversation conv) {
        if (conv.getAgentSessionId() == null) {
            return List.of();
        }
        List<AgentKbZeroHitLog> logs = zeroHitMapper.selectList(
            new LambdaQueryWrapper<AgentKbZeroHitLog>()
                .eq(AgentKbZeroHitLog::getSessionId, conv.getAgentSessionId())
                .orderByDesc(AgentKbZeroHitLog::getId)
                .last("limit 3"));
        List<String> questions = new ArrayList<>();
        for (AgentKbZeroHitLog log : logs) {
            if (log.getOriginalQuery() != null && !log.getOriginalQuery().isBlank()) {
                questions.add(log.getOriginalQuery());
            }
        }
        return questions;
    }

    /** 最近几轮访客原话(正序),与 A3 意图兜底同口径:只取客户说的话。 */
    private List<String> recentVisitorTexts(CsConversation conv) {
        if (conv.getAgentSessionId() == null) {
            return List.of();
        }
        List<AgentMessage> rows = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, conv.getAgentSessionId())
            .orderByDesc(AgentMessage::getId)
            .last("limit 10"));
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
        int from = Math.max(0, texts.size() - EMOTION_TURNS);
        return texts.subList(from, texts.size());
    }

    private static boolean isVisitorMessage(AgentMessage row) {
        String sender = row.getSenderType();
        if (sender != null && !sender.isBlank()) {
            return !Set.of("seat", "assistant", "system", "bot", "robot").contains(sender.toLowerCase());
        }
        return row.getRole() == null || "user".equalsIgnoreCase(row.getRole());
    }

    private void upsert(Long workspaceId, Long conversationId, Long ticketId,
                        Map<String, Object> summary, int turns) throws Exception {
        CsHandoffSummary row = summaryMapper.selectOne(new LambdaQueryWrapper<CsHandoffSummary>()
            .eq(CsHandoffSummary::getConversationId, conversationId).last("limit 1"));
        boolean insert = row == null;
        if (insert) {
            row = new CsHandoffSummary();
            row.setConversationId(conversationId);
            row.setCreateTime(new Date());
        }
        row.setWorkspaceId(workspaceId);
        row.setTicketId(ticketId);
        row.setSummaryJson(MAPPER.writeValueAsString(summary));
        row.setSourceVersion(turns);
        row.setDegraded(Boolean.TRUE.equals(summary.get("degraded")) ? "1" : "0");
        row.setUpdateTime(new Date());
        if (insert) {
            summaryMapper.insert(row);
        } else {
            summaryMapper.updateById(row);
        }
    }
}
