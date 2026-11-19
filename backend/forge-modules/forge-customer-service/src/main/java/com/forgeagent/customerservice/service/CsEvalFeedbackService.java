package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsBadcase;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 评测回流服务(批次 D2)。
 *
 * <p>把客服域产生的样本直接沉淀进平台评测集 {@code agent_eval_case},取代原来
 * 「导出 JSON → 运营手工导入」的搬运环节。两类来源:</p>
 * <ul>
 *   <li>{@code cs_badcase} —— 运营在改进单里「标记评测」,立即推送;</li>
 *   <li>{@code cs_sampled} —— 坐席人工结案且质量达标的会话,按比例抽样。</li>
 * </ul>
 *
 * <p>两条路都写 {@code review_status='0'}(待确认),由运营确认后才参与评测运行——
 * 未经确认的样本进评测集会稀释基线,也让门禁数字失去意义。幂等靠唯一键
 * {@code uk_eval_case_source(source, source_ref_id)} 兜住,重放不会产生重复用例。</p>
 *
 * <p>评测引擎仍在平台侧,这里只做写入与审核,不引 agent 模块的 Mapper。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@ConditionalOnCustomerServiceEnabled
public class CsEvalFeedbackService {

    /** 客服回流评测集名称(按 workspace + agent 定位,不存在则建) */
    public static final String DATASET_NAME = "客服回流用例";

    public static final String SOURCE_BADCASE = "cs_badcase";
    public static final String SOURCE_SAMPLED = "cs_sampled";

    /** 待确认 / 已通过 / 已驳回 */
    public static final String REVIEW_PENDING = "0";
    public static final String REVIEW_APPROVED = "1";
    public static final String REVIEW_REJECTED = "2";

    /** 抽样门槛:访客至少说两轮,单轮会话没有评测价值 */
    private static final int MIN_VISITOR_TURNS = 2;
    private static final int QUESTION_MAX = 2000;
    private static final int REFERENCE_MAX = 8000;

    private final JdbcTemplate jdbc;
    private final CsModuleProperties properties;

    public CsEvalFeedbackService(JdbcTemplate jdbc, CsModuleProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * badcase「标记评测」→ 写入评测集(待确认)。
     *
     * @return 新用例 id;幂等重放时返回已存在的那条
     */
    public Long pushBadcase(CsBadcase badcase) {
        if (badcase == null || StringUtils.isBlank(badcase.getQuestion())) {
            throw new ServiceException("该 badcase 没有问题文本,无法作为评测用例");
        }
        Long agentId = agentIdOfConversation(badcase.getConversationId());
        Long datasetId = ensureDataset(badcase.getWorkspaceId(), agentId);
        return insertCase(datasetId, SOURCE_BADCASE, badcase.getId(),
            badcase.getQuestion(), badcase.getAnswer());
    }

    /**
     * 坐席人工结案后的采样(批次 D2)。
     *
     * <p>只在 {@code actorType} 不是 SYSTEM 时调用;失败一律吞掉——采样是旁路能力,
     * 不能因为写评测集失败把结案流程带崩。</p>
     */
    public void sampleFromConversation(CsConversation conv, CsTicket ticket) {
        if (conv == null || conv.getId() == null) {
            return;
        }
        try {
            double rate = sampleRate();
            if (rate <= 0 || ThreadLocalRandom.current().nextDouble() >= rate) {
                return;
            }
            if (conv.getAgentId() == null || conv.getAgentSessionId() == null) {
                return;
            }
            Map<String, Object> lastVisitor = lastMessage(conv.getAgentSessionId(), "user");
            if (lastVisitor == null || StringUtils.isBlank(text(lastVisitor.get("content")))) {
                return;
            }
            Integer turns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_message WHERE session_id=? AND role='user'",
                Integer.class, conv.getAgentSessionId());
            if (turns == null || turns < MIN_VISITOR_TURNS) {
                return;
            }
            Integer badcases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_cs_badcase WHERE conversation_id=?", Integer.class, conv.getId());
            if (badcases != null && badcases > 0) {
                // 已经进过改进单的会话,走人工标记路径,不重复采样
                return;
            }
            String reference = referenceAnswer(conv, ticket);
            if (StringUtils.isBlank(reference)) {
                return;
            }
            Long datasetId = ensureDataset(conv.getWorkspaceId(), conv.getAgentId());
            Long caseId = insertCase(datasetId, SOURCE_SAMPLED, conv.getId(),
                text(lastVisitor.get("content")), reference);
            log.info("[CSP][D2] 会话 {} 采样入评测集, caseId={}", conv.getId(), caseId);
        } catch (Exception e) {
            log.warn("[CSP][D2] 会话 {} 采样失败: {}", conv.getId(), e.getMessage());
        }
    }

    /** 待确认/已通过/已驳回用例列表(按 workspace 圈定数据集) */
    public List<Map<String, Object>> listCases(Long workspaceId, String reviewStatus, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id,c.dataset_id,c.question,c.reference_answer,c.source,c.source_ref_id,
                       c.review_status,c.create_time
                FROM agent_eval_case c
                JOIN agent_eval_dataset d ON d.id = c.dataset_id AND d.del_flag = 0
                WHERE d.workspace_id = ? AND c.source IS NOT NULL
                """);
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        if (StringUtils.isNotBlank(reviewStatus)) {
            sql.append(" AND c.review_status = ? ");
            args.add(reviewStatus);
        }
        sql.append(" ORDER BY c.id DESC LIMIT ? ");
        args.add(Math.max(1, Math.min(200, limit)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 确认或驳回一条回流用例 */
    public void review(Long caseId, Long workspaceId, boolean approve) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id,c.review_status FROM agent_eval_case c
                JOIN agent_eval_dataset d ON d.id = c.dataset_id AND d.del_flag = 0
                WHERE c.id = ? AND d.workspace_id = ?
                """, caseId, workspaceId);
        if (rows.isEmpty()) {
            throw new ServiceException("评测用例不存在");
        }
        jdbc.update("UPDATE agent_eval_case SET review_status=? WHERE id=?",
            approve ? REVIEW_APPROVED : REVIEW_REJECTED, caseId);
    }

    /** 待确认数量(运营页角标) */
    public int pendingCount(Long workspaceId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_eval_case c
                JOIN agent_eval_dataset d ON d.id = c.dataset_id AND d.del_flag = 0
                WHERE d.workspace_id = ? AND c.source IS NOT NULL AND c.review_status = '0'
                """, Integer.class, workspaceId);
        return count == null ? 0 : count;
    }

    /**
     * 定位/创建该 Agent 的客服回流评测集。
     * 一个客服 Agent 一个集子,避免同空间多个客服的样本混在一起拉低基线。
     */
    public Long ensureDataset(Long workspaceId, Long agentId) {
        if (workspaceId == null) {
            throw new ServiceException("工作空间不能为空");
        }
        List<Long> exists = agentId == null
            ? jdbc.queryForList("""
                SELECT id FROM agent_eval_dataset
                WHERE workspace_id=? AND agent_id IS NULL AND name=? AND del_flag=0
                ORDER BY id LIMIT 1
                """, Long.class, workspaceId, DATASET_NAME)
            : jdbc.queryForList("""
                SELECT id FROM agent_eval_dataset
                WHERE workspace_id=? AND agent_id=? AND name=? AND del_flag=0
                ORDER BY id LIMIT 1
                """, Long.class, workspaceId, agentId, DATASET_NAME);
        if (!exists.isEmpty()) {
            return exists.getFirst();
        }
        jdbc.update("""
                INSERT INTO agent_eval_dataset (workspace_id,agent_id,name,description,create_time)
                VALUES (?,?,?,?,NOW())
                """, workspaceId, agentId, DATASET_NAME, "客服 badcase 与坐席优质会话自动沉淀(D2)");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * 写一条待确认用例。唯一键冲突说明该 badcase/会话已推过,直接取回原 id。
     */
    private Long insertCase(Long datasetId, String source, Long sourceRefId,
                            String question, String reference) {
        jdbc.update("""
                INSERT IGNORE INTO agent_eval_case
                  (dataset_id,question,expected_keywords,reference_answer,expected_refusal,
                   source,source_ref_id,review_status,create_time)
                VALUES (?,?,NULL,?,0,?,?,?,NOW())
                """, datasetId, truncate(question, QUESTION_MAX),
            truncate(reference, REFERENCE_MAX), source, sourceRefId, REVIEW_PENDING);
        List<Long> id = jdbc.queryForList("""
                SELECT id FROM agent_eval_case WHERE source=? AND source_ref_id=? ORDER BY id LIMIT 1
                """, Long.class, source, sourceRefId);
        return id.isEmpty() ? null : id.getFirst();
    }

    private Map<String, Object> lastMessage(Long sessionId, String role) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT content FROM agent_message WHERE session_id=? AND role=?
                ORDER BY id DESC LIMIT 1
                """, sessionId, role);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 参考答案优先取坐席最后一条回复,退化为结案备注 */
    private String referenceAnswer(CsConversation conv, CsTicket ticket) {
        Map<String, Object> lastSeat = lastMessage(conv.getAgentSessionId(), "assistant");
        if (lastSeat != null && StringUtils.isNotBlank(text(lastSeat.get("content")))) {
            return text(lastSeat.get("content"));
        }
        if (ticket != null && StringUtils.isNotBlank(ticket.getResolveNote())) {
            return ticket.getResolveNote();
        }
        return null;
    }

    private Long agentIdOfConversation(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
            "SELECT agent_id FROM agent_cs_conversation WHERE id=?", Long.class, conversationId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private double sampleRate() {
        Double rate = properties.getEvalSampleRate();
        if (rate == null) {
            return 0.1;
        }
        return Math.max(0, Math.min(1, rate));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 供运维页展示当前采样比例 */
    public Map<String, Object> sampleInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sampleRate", sampleRate());
        info.put("minVisitorTurns", MIN_VISITOR_TURNS);
        info.put("datasetName", DATASET_NAME);
        return info;
    }
}
