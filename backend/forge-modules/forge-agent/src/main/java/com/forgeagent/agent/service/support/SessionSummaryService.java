package com.forgeagent.agent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agentcore.service.UtilityAgents;
import com.forgeagent.agentcore.utils.ChatTexts;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.service.document.TokenEstimator;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * L2 滚动摘要服务(P3 / M1)。
 *
 * <p>每轮对话结束后投递一次异步任务:当「摘要水位之后、最近 N 条之外」的未压缩消息
 * 累计超过 token 阈值时,用 Agent 绑定模型把旧摘要 + 增量对话压成一段新摘要,
 * 并以 CAS 推进 {@code summary_upto_msg_id}。下一轮装配只注入摘要块 + 水位之后的原始消息。</p>
 *
 * <p>纪律:独立 daemon 单线程 + 有界队列,队列满直接丢弃(下一轮会再投递);
 * 模型缺失/调用失败/并发冲突都只记日志,绝不影响对话主链路。调试临时会话不生成摘要。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSummaryService {

    private static final String SUMMARY_SYS_PROMPT =
        "你是会话记忆压缩助手。把【已有摘要】与【新增对话】合并成一段连续的中文摘要,"
            + "保留用户偏好、事实数据、编号、已做出的决定、待办与未解决的问题,"
            + "删除寒暄、重复与过程性推演;摘要要能独立支撑后续对话理解早期内容。"
            + "只输出摘要正文,不要标题、引号、列表符号或任何解释,不超过 400 字。";

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
        r -> {
            Thread t = new Thread(r, "agent-session-summary");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());

    /** 同一会话同时只允许一个摘要任务在途 */
    private final Map<Long, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentConfigCacheService agentConfigCache;
    private final AgentChatProperties chatProperties;

    /** L0 装配用:已生成摘要时返回带小节标题的摘要块 */
    public String summaryBlock(AgentSession session) {
        if (session == null || !enabled() || upto(session) <= 0
            || StringUtils.isBlank(session.getSummaryText())) {
            return "";
        }
        return "[会话摘要]\n" + session.getSummaryText();
    }

    /** L2 历史过滤水位:该 id 及之前的消息已被摘要覆盖 */
    public long upto(AgentSession session) {
        return session == null || session.getSummaryUptoMsgId() == null
            ? 0L : Math.max(0L, session.getSummaryUptoMsgId());
    }

    /** 一轮对话结束后投递摘要检查(异步、去重、静默失败) */
    public void schedule(Long sessionId, Long agentId) {
        if (!enabled() || sessionId == null) {
            return;
        }
        AtomicBoolean flag = inFlight.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            return;
        }
        try {
            WORKER.execute(() -> {
                try {
                    doSummarize(sessionId, agentId);
                } catch (Exception e) {
                    log.warn("[SessionSummary] 会话 {} 摘要生成失败(不影响对话): {}", sessionId, e.getMessage());
                } finally {
                    flag.set(false);
                    inFlight.remove(sessionId, flag);
                }
            });
        } catch (Exception e) {
            flag.set(false);
            inFlight.remove(sessionId, flag);
            log.warn("[SessionSummary] 摘要任务投递失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private void doSummarize(Long sessionId, Long agentId) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session == null || (session.getTemp() != null && session.getTemp() == 1)) {
            return;
        }
        int keepRecent = intValue(chatProperties.getSummaryKeepRecentMessages(), 4);
        // 最近 keepRecent 条保持原始消息,只压缩更早的部分
        List<AgentMessage> recent = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, sessionId)
            .orderByDesc(AgentMessage::getId)
            .last("limit " + Math.max(1, keepRecent)));
        if (recent.size() < keepRecent) {
            return;
        }
        long boundary = recent.getLast().getId();
        long watermark = upto(session);
        if (boundary <= watermark + 1) {
            return;
        }
        List<AgentMessage> pending = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, sessionId)
            .gt(AgentMessage::getId, watermark)
            .lt(AgentMessage::getId, boundary)
            .orderByAsc(AgentMessage::getId)
            .last("limit 100"));
        if (pending.isEmpty()) {
            return;
        }
        int tokens = pending.stream().mapToInt(m -> TokenEstimator.estimate(m.getContent())).sum();
        int trigger = intValue(chatProperties.getSummaryTriggerTokens(), 1500);
        if (tokens < trigger && pending.size() < 100) {
            return;
        }

        AgentModel model = resolveModel(agentId);
        if (model == null) {
            return;
        }
        String input = buildInput(session.getSummaryText(), pending);
        Msg reply = UtilityAgents.build("session-summarizer", SUMMARY_SYS_PROMPT, model)
            .call(Msg.builder().name("user").role(MsgRole.USER).textContent(input).build())
            .block(Duration.ofSeconds(30));
        String text = reply == null ? null : clean(reply.getTextContent());
        if (StringUtils.isBlank(text)) {
            return;
        }
        Long lastId = pending.getLast().getId();
        AgentSession update = new AgentSession();
        update.setSummaryText(text);
        update.setSummaryUptoMsgId(lastId);
        // CAS:水位未变才写入,避免两个任务并发把摘要覆盖成旧版本
        int rows = sessionMapper.update(update, Wrappers.<AgentSession>lambdaUpdate()
            .eq(AgentSession::getId, sessionId)
            .eq(AgentSession::getSummaryUptoMsgId, watermark));
        if (rows > 0) {
            log.info("[SessionSummary] 会话 {} 摘要推进至消息 {},压缩 {} 条 / 约 {} tokens",
                sessionId, lastId, pending.size(), tokens);
        }
    }

    private String buildInput(String existing, List<AgentMessage> pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("【已有摘要】\n")
            .append(StringUtils.isBlank(existing) ? "(暂无)" : ChatTexts.truncate(existing, 800))
            .append("\n\n【新增对话】\n");
        for (AgentMessage m : pending) {
            sb.append("user".equals(m.getRole()) ? "用户: " : "助手: ")
                .append(ChatTexts.truncate(StringUtils.defaultString(m.getContent()), 500))
                .append('\n');
            if (sb.length() >= 8000) {
                break;
            }
        }
        return sb.toString();
    }

    private AgentModel resolveModel(Long agentId) {
        if (agentId == null) {
            return null;
        }
        var agent = agentConfigCache.getAgentById(agentId);
        if (agent == null || agent.getModelId() == null) {
            return null;
        }
        AgentModel model = agentConfigCache.getModelById(agent.getModelId());
        if (model == null || StringUtils.isBlank(model.getBaseUrl())
            || StringUtils.isBlank(model.getApiKey()) || StringUtils.isBlank(model.getModelName())) {
            return null;
        }
        return model;
    }

    private String clean(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String text = raw.trim()
            .replaceAll("[\\r\\n]+", " ")
            .replaceAll("^[\"'“”《【]+|[\"'“”。.】》]+$", "")
            .trim();
        int max = intValue(chatProperties.getSummaryMaxChars(), 800);
        return ChatTexts.truncate(text, max);
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(chatProperties.getSummaryEnabled());
    }

    private static int intValue(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
