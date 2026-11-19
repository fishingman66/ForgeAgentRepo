package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsBadcase;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.callcenter.common.EvalFeedbackWriter;
import com.forgeagent.customerservice.mapper.CsBadcaseMapper;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

    private final EvalFeedbackWriter evalWriter;
    private final CsBadcaseMapper badcaseMapper;
    private final CsConversationMapper conversationMapper;
    private final CsModuleProperties properties;

    public CsEvalFeedbackService(EvalFeedbackWriter evalWriter, CsBadcaseMapper badcaseMapper,
                                 CsConversationMapper conversationMapper, CsModuleProperties properties) {
        this.evalWriter = evalWriter;
        this.badcaseMapper = badcaseMapper;
        this.conversationMapper = conversationMapper;
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
        Long datasetId = evalWriter.ensureDataset(badcase.getWorkspaceId(), agentId,
            DATASET_NAME, "客服 badcase 与坐席优质会话自动沉淀(D2)");
        return evalWriter.insertCase(datasetId, SOURCE_BADCASE, badcase.getId(),
            badcase.getQuestion(), badcase.getAnswer(), REVIEW_PENDING);
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
            Map<String, Object> lastVisitor = evalWriter.lastMessage(conv.getAgentSessionId(), "user");
            if (lastVisitor == null || StringUtils.isBlank(text(lastVisitor.get("content")))) {
                return;
            }
            int turns = evalWriter.countVisitorTurns(conv.getAgentSessionId());
            if (turns < MIN_VISITOR_TURNS) {
                return;
            }
            Long badcases = badcaseMapper.selectCount(Wrappers.<com.forgeagent.customerservice.domain.CsBadcase>lambdaQuery()
                .eq(com.forgeagent.customerservice.domain.CsBadcase::getConversationId, conv.getId()));
            if (badcases != null && badcases > 0) {
                // 已经进过改进单的会话,走人工标记路径,不重复采样
                return;
            }
            String reference = referenceAnswer(conv, ticket);
            if (StringUtils.isBlank(reference)) {
                return;
            }
            Long datasetId = evalWriter.ensureDataset(conv.getWorkspaceId(), conv.getAgentId(),
                DATASET_NAME, "客服 badcase 与坐席优质会话自动沉淀(D2)");
            Long caseId = evalWriter.insertCase(datasetId, SOURCE_SAMPLED, conv.getId(),
                text(lastVisitor.get("content")), reference, REVIEW_PENDING);
            log.info("[CSP][D2] 会话 {} 采样入评测集, caseId={}", conv.getId(), caseId);
        } catch (Exception e) {
            log.warn("[CSP][D2] 会话 {} 采样失败: {}", conv.getId(), e.getMessage());
        }
    }

    /** 待确认/已通过/已驳回用例列表(按 workspace 圈定数据集) */
    public List<com.forgeagent.customerservice.domain.vo.CsEvalCaseRowVo> listCases(Long workspaceId, String reviewStatus, int limit) {
        List<Map<String, Object>> rows = evalWriter.listCases(workspaceId, StringUtils.isBlank(reviewStatus) ? null : reviewStatus,
            Math.max(1, Math.min(200, limit)));
        List<com.forgeagent.customerservice.domain.vo.CsEvalCaseRowVo> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            com.forgeagent.customerservice.domain.vo.CsEvalCaseRowVo vo = new com.forgeagent.customerservice.domain.vo.CsEvalCaseRowVo();
            vo.setId(((Number) row.get("id")).longValue());
            vo.setDatasetId(row.get("dataset_id") == null ? null : ((Number) row.get("dataset_id")).longValue());
            vo.setQuestion((String) row.get("question"));
            vo.setReferenceAnswer((String) row.get("reference_answer"));
            vo.setSource((String) row.get("source"));
            vo.setSourceRefId(row.get("source_ref_id") == null ? null : ((Number) row.get("source_ref_id")).longValue());
            vo.setReviewStatus((String) row.get("review_status"));
            vo.setCreateTime((java.util.Date) row.get("create_time"));
            result.add(vo);
        }
        return result;
    }

    /** 确认或驳回一条回流用例 */
    public void review(Long caseId, Long workspaceId, boolean approve) {
        evalWriter.review(caseId, workspaceId, approve, REVIEW_APPROVED, REVIEW_REJECTED);
    }

    /** 待确认数量(运营页角标) */
    public int pendingCount(Long workspaceId) {
        return evalWriter.pendingCount(workspaceId, REVIEW_PENDING);
    }

    /**
     * 定位/创建该 Agent 的客服回流评测集。
     * 一个客服 Agent 一个集子,避免同空间多个客服的样本混在一起拉低基线。
     */
    public Long ensureDataset(Long workspaceId, Long agentId) {
        if (workspaceId == null) {
            throw new ServiceException("工作空间不能为空");
        }
        return evalWriter.ensureDataset(workspaceId, agentId,
            DATASET_NAME, "客服 badcase 与坐席优质会话自动沉淀(D2)");
    }

    /** 参考答案优先取坐席最后一条回复,退化为结案备注 */
    private String referenceAnswer(CsConversation conv, CsTicket ticket) {
        Map<String, Object> lastSeat = evalWriter.lastMessage(conv.getAgentSessionId(), "assistant");
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
        com.forgeagent.customerservice.domain.CsConversation conv = conversationMapper.selectById(conversationId);
        return conv == null ? null : conv.getAgentId();
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
    public com.forgeagent.customerservice.domain.vo.CsEvalSampleInfoVo sampleInfo() {
        com.forgeagent.customerservice.domain.vo.CsEvalSampleInfoVo info = new com.forgeagent.customerservice.domain.vo.CsEvalSampleInfoVo();
        info.setSampleRate(sampleRate());
        info.setMinVisitorTurns(MIN_VISITOR_TURNS);
        info.setDatasetName(DATASET_NAME);
        return info;
    }
}
