package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentEvalCase;
import com.forgeagent.agent.domain.AgentEvalDataset;
import com.forgeagent.agent.domain.AgentEvalRun;
import com.forgeagent.agent.mapper.AgentEvalCaseMapper;
import com.forgeagent.agent.mapper.AgentEvalDatasetMapper;
import com.forgeagent.agent.mapper.AgentEvalRunMapper;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.callcenter.common.EvalFeedbackWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 客服回流评测写入契约的 agent 侧实现(方向 B 跨域解环)。
 *
 * <p>客服模块经 common 层 {@link EvalFeedbackWriter} 调用,本实现持有评测域 Mapper,
 * 让平台表的访问留在 agent 域,不形成反向依赖。</p>
 */
@Slf4j
@Service
public class EvalFeedbackWriterImpl implements EvalFeedbackWriter {

    private final AgentEvalDatasetMapper datasetMapper;
    private final AgentEvalCaseMapper caseMapper;
    private final AgentMessageMapper messageMapper;

    public EvalFeedbackWriterImpl(AgentEvalDatasetMapper datasetMapper,
                                  AgentEvalCaseMapper caseMapper,
                                  AgentMessageMapper messageMapper) {
        this.datasetMapper = datasetMapper;
        this.caseMapper = caseMapper;
        this.messageMapper = messageMapper;
    }

    @Override
    public Long ensureDataset(Long workspaceId, Long agentId, String datasetName, String description) {
        List<Long> exists = datasetMapper.findId(workspaceId, agentId, datasetName);
        if (!exists.isEmpty()) {
            return exists.getFirst();
        }
        AgentEvalDataset entity = new AgentEvalDataset();
        entity.setWorkspaceId(workspaceId);
        entity.setAgentId(agentId);
        entity.setName(datasetName);
        entity.setDescription(description);
        entity.setCreateTime(new java.util.Date());
        datasetMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public Long insertCase(Long datasetId, String source, Long sourceRefId,
                           String question, String reference, String pendingStatus) {
        AgentEvalCase entity = new AgentEvalCase();
        entity.setDatasetId(datasetId);
        entity.setQuestion(question);
        entity.setReferenceAnswer(reference);
        entity.setExpectedRefusal(0);
        entity.setSource(source);
        entity.setSourceRefId(sourceRefId);
        entity.setReviewStatus(pendingStatus);
        entity.setCreateTime(new java.util.Date());
        try {
            caseMapper.insert(entity);
            return entity.getId();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一键(source,source_ref_id)冲突=重放,取回原 id(原 INSERT IGNORE 语义)
            List<Long> ids = caseMapper.selectList(Wrappers.<AgentEvalCase>lambdaQuery()
                    .eq(AgentEvalCase::getSource, source)
                    .eq(AgentEvalCase::getSourceRefId, sourceRefId))
                .stream().map(AgentEvalCase::getId).toList();
            return ids.isEmpty() ? null : ids.getFirst();
        }
    }

    @Override
    public List<Map<String, Object>> listCases(Long workspaceId, String reviewStatus, int limit) {
        return caseMapper.listFeedbackCases(workspaceId, reviewStatus, limit);
    }

    @Override
    public void review(Long caseId, Long workspaceId, boolean approve,
                       String approvedStatus, String rejectedStatus) {
        if (caseMapper.checkOwnership(caseId, workspaceId).isEmpty()) {
            throw new com.forgeagent.common.core.exception.ServiceException("评测用例不存在");
        }
        AgentEvalCase entity = new AgentEvalCase();
        entity.setId(caseId);
        entity.setReviewStatus(approve ? approvedStatus : rejectedStatus);
        caseMapper.updateById(entity);
    }

    @Override
    public int pendingCount(Long workspaceId, String pendingStatus) {
        return (int) caseMapper.countPending(workspaceId, pendingStatus);
    }

    @Override
    public Map<String, Object> lastMessage(Long sessionId, String role) {
        List<com.forgeagent.agent.domain.AgentMessage> rows = messageMapper.selectList(
            Wrappers.<com.forgeagent.agent.domain.AgentMessage>lambdaQuery()
                .eq(com.forgeagent.agent.domain.AgentMessage::getSessionId, sessionId)
                .eq(com.forgeagent.agent.domain.AgentMessage::getRole, role)
                .orderByDesc(com.forgeagent.agent.domain.AgentMessage::getId)
                .last("limit 1"));
        if (rows.isEmpty()) {
            return null;
        }
        com.forgeagent.agent.domain.AgentMessage m = rows.getFirst();
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("content", m.getContent());
        return map;
    }

    @Override
    public int countVisitorTurns(Long sessionId) {
        Long count = messageMapper.selectCount(
            Wrappers.<com.forgeagent.agent.domain.AgentMessage>lambdaQuery()
                .eq(com.forgeagent.agent.domain.AgentMessage::getSessionId, sessionId)
                .eq(com.forgeagent.agent.domain.AgentMessage::getRole, "user"));
        return count == null ? 0 : count.intValue();
    }
}
