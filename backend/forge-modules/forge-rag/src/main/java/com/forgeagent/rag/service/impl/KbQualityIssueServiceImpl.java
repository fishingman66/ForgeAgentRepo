package com.forgeagent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKbEvalCase;
import com.forgeagent.rag.domain.AgentKbQualityIssue;
import com.forgeagent.rag.domain.vo.AgentKbQualityIssueVo;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKbEvalCaseMapper;
import com.forgeagent.rag.mapper.AgentKbQualityIssueMapper;
import com.forgeagent.rag.service.KbEvalService;
import com.forgeagent.rag.service.KbQualityIssueService;
import com.forgeagent.rag.service.support.QueryHashes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Set;/**
 * 知识库质量信息服务实现(R3-5)
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KbQualityIssueServiceImpl implements KbQualityIssueService {

    private static final Set<String> REASONS = Set.of("MISSING_KNOWLEDGE", "OUTDATED_KNOWLEDGE",
        "RETRIEVAL_MISS", "ANSWER_INCORRECT", "OTHER");
    private static final Set<String> SOURCES = Set.of("ZERO_HIT", "DEBUG_PAGE", "MANUAL", "CASE_FAIL");
    private static final Set<String> STATUSES = Set.of("NEW", "IN_PROGRESS", "RESOLVED");

    private final AgentKbQualityIssueMapper issueMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final AgentKbEvalCaseMapper evalCaseMapper;
    private final KbEvalService evalService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(Long knowledgeId, String queryText, String reason, String source,
                         Long sourceId, String note) {
        if (knowledgeId == null || StringUtils.isBlank(queryText)) {
            throw new ServiceException("知识库ID与查询原文不能为空");
        }
        String r = StringUtils.defaultIfBlank(reason, "RETRIEVAL_MISS");
        String s = StringUtils.defaultIfBlank(source, "ZERO_HIT");
        if (!REASONS.contains(r)) {
            throw new ServiceException("未知的问题原因:" + r);
        }
        if (!SOURCES.contains(s)) {
            throw new ServiceException("未知的来源类型:" + s);
        }
        String hash = QueryHashes.of(queryText);
        AgentKbQualityIssue existing = issueMapper.selectOne(new LambdaQueryWrapper<AgentKbQualityIssue>()
            .eq(AgentKbQualityIssue::getKnowledgeId, knowledgeId)
            .eq(AgentKbQualityIssue::getSource, s)
            .eq(AgentKbQualityIssue::getQueryHash, hash)
            .last("limit 1"));
        if (existing != null) {
            // 同来源同指纹只登记一次;RESOLVED 后再次出现视为复发,回 IN_PROGRESS
            if ("RESOLVED".equals(existing.getStatus())) {
                reopen(existing.getId(), "再次出现,自动reopen", null);
            }
            return existing.getId();
        }
        AgentKbQualityIssue issue = new AgentKbQualityIssue();
        issue.setKnowledgeId(knowledgeId);
        issue.setQueryText(StringUtils.substring(queryText, 0, 1000));
        issue.setQueryHash(hash);
        issue.setReason(r);
        issue.setSource(s);
        issue.setSourceId(sourceId);
        issue.setNote(StringUtils.defaultString(note));
        issue.setStatus("NEW");
        issue.setRevision(0);
        issue.setCreateTime(new Date());
        issueMapper.insert(issue);
        return issue.getId();
    }

    @Override
    public PageResult<AgentKbQualityIssueVo> page(Long knowledgeId, String status, PageQuery pageQuery) {
        Page<AgentKbQualityIssueVo> page = issueMapper.selectVoPage(pageQuery.build(),
            new LambdaQueryWrapper<AgentKbQualityIssue>()
                .eq(AgentKbQualityIssue::getKnowledgeId, knowledgeId)
                .eq(StringUtils.isNotBlank(status), AgentKbQualityIssue::getStatus, status)
                .orderByDesc(AgentKbQualityIssue::getId));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public void changeStatus(Long id, String target, String note, Integer revision, String operator) {
        AgentKbQualityIssue issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new ServiceException("质量问题不存在");
        }
        if (!STATUSES.contains(target)) {
            throw new ServiceException("未知状态:" + target);
        }
        String current = StringUtils.defaultString(issue.getStatus(), "NEW");
        boolean allowed = switch (target) {
            case "IN_PROGRESS" -> "NEW".equals(current) || "RESOLVED".equals(current);
            case "RESOLVED" -> "IN_PROGRESS".equals(current) || "NEW".equals(current);
            case "NEW" -> false;
            default -> false;
        };
        if (!allowed) {
            throw new ServiceException("不允许的状态流转:" + current + " → " + target);
        }
        if (revision == null || issue.getRevision() == null || revision != issue.getRevision()) {
            throw new ServiceException("数据已被他人修改(revision 不一致),请刷新后重试");
        }
        issueMapper.update(null, new LambdaUpdateWrapper<AgentKbQualityIssue>()
            .eq(AgentKbQualityIssue::getId, id)
            .eq(AgentKbQualityIssue::getRevision, revision)
            .set(AgentKbQualityIssue::getStatus, target)
            .set(AgentKbQualityIssue::getNote, StringUtils.isNotBlank(note) ? note : issue.getNote())
            .set(AgentKbQualityIssue::getOwner, StringUtils.defaultString(operator, issue.getOwner()))
            .set(AgentKbQualityIssue::getUpdateTime, new Date())
            .set("RESOLVED".equals(target), AgentKbQualityIssue::getResolveTime, new Date())
            .set(AgentKbQualityIssue::getRevision, revision + 1));
    }

    /**
     * 复发自动 reopen(不校验 revision,内部调用)
     */
    private void reopen(Long id, String note, String operator) {
        AgentKbQualityIssue issue = issueMapper.selectById(id);
        if (issue == null) {
            return;
        }
        issueMapper.update(null, new LambdaUpdateWrapper<AgentKbQualityIssue>()
            .eq(AgentKbQualityIssue::getId, id)
            .eq(AgentKbQualityIssue::getRevision, issue.getRevision())
            .set(AgentKbQualityIssue::getStatus, "IN_PROGRESS")
            .set(AgentKbQualityIssue::getNote, note)
            .set(AgentKbQualityIssue::getUpdateTime, new Date())
            .set(AgentKbQualityIssue::getRevision, issue.getRevision() + 1));
        log.info("[知识库质量] 质量问题 {} 复发自动 reopen: {}", id, note);
    }

    @Override
    public void linkCase(Long id, Long caseId) {
        issueMapper.update(null, new LambdaUpdateWrapper<AgentKbQualityIssue>()
            .eq(AgentKbQualityIssue::getId, id)
            .set(AgentKbQualityIssue::getLinkedCaseId, caseId)
            .set(AgentKbQualityIssue::getUpdateTime, new Date()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCaseFromHit(Long knowledgeId, String question, Long documentId,
                                  Long chunkId, String spanOverride) {
        if (knowledgeId == null || StringUtils.isBlank(question)) {
            throw new ServiceException("知识库ID与评测问题不能为空");
        }
        if (documentId == null) {
            throw new ServiceException("必须指定证据文档");
        }
        String span = spanOverride;
        if (StringUtils.isBlank(span) && chunkId != null) {
            AgentKbChunk chunk = chunkMapper.selectById(chunkId);
            if (chunk == null || !knowledgeId.equals(chunk.getKnowledgeId())) {
                throw new ServiceException("指定分块不存在或不属于该知识库");
            }
            span = chunk.getContent();
        }
        if (StringUtils.isBlank(span)) {
            throw new ServiceException("证据 span 为空:请指定分块或直接提供原文片段");
        }
        String evidence = "[{\"documentId\":" + documentId + ",\"span\":"
            + quoteJson(span) + "}]";
        AgentKbEvalCase evalCase = new AgentKbEvalCase();
        evalCase.setKnowledgeId(knowledgeId);
        evalCase.setQuestion(StringUtils.substring(question.trim(), 0, 1000));
        evalCase.setEvidences(evidence);
        evalCase.setStatus("ACTIVE");
        evalCase.setSource("DEBUG_PAGE");
        evalCase.setSourceId(chunkId);
        return evalService.saveCase(evalCase);
    }

    private static String quoteJson(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
