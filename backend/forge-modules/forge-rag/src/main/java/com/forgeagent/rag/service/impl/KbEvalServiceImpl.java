package com.forgeagent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.rag.domain.AgentKbEvalCase;
import com.forgeagent.rag.domain.AgentKbEvalRun;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.mapper.AgentKbEvalCaseMapper;
import com.forgeagent.rag.mapper.AgentKbEvalRunMapper;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.rag.service.KbEvalService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索评测服务实现
 *
 * <p>命中判定:命中结果中存在同时满足「期望文档名(模糊包含)」与
 * 「期望关键词(任一包含)」的条目即为命中;MRR 取首个满足条目的倒数排名。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KbEvalServiceImpl implements KbEvalService {

    private final AgentKbEvalCaseMapper caseMapper;
    private final AgentKbEvalRunMapper runMapper;
    private final IAgentKnowledgeService knowledgeService;

    @Override
    public Long saveCase(AgentKbEvalCase evalCase) {
        if (evalCase.getKnowledgeId() == null || StringUtils.isBlank(evalCase.getQuestion())) {
            throw new ServiceException("知识库ID与评测问题不能为空");
        }
        if (evalCase.getCreateTime() == null) {
            evalCase.setCreateTime(new Date());
        }
        caseMapper.insert(evalCase);
        return evalCase.getId();
    }

    @Override
    public void deleteCase(Long id) {
        caseMapper.deleteById(id);
    }

    @Override
    public List<AgentKbEvalCase> listCases(Long knowledgeId) {
        return caseMapper.selectList(new LambdaQueryWrapper<AgentKbEvalCase>()
            .eq(AgentKbEvalCase::getKnowledgeId, knowledgeId)
            .orderByAsc(AgentKbEvalCase::getId));
    }

    @Override
    public Map<String, Object> runEvaluation(Long knowledgeId, Integer topK) {
        List<AgentKbEvalCase> cases = listCases(knowledgeId);
        if (cases.isEmpty()) {
            throw new ServiceException("该知识库还没有评测用例,请先添加");
        }
        int k = topK == null || topK <= 0 ? 5 : topK;
        long start = System.currentTimeMillis();

        int hitCases = 0;
        double reciprocalSum = 0;
        double scoreSum = 0;
        int scoreCount = 0;
        List<Map<String, Object>> caseResults = new ArrayList<>(cases.size());

        for (AgentKbEvalCase evalCase : cases) {
            KnowledgeSearchBo bo = new KnowledgeSearchBo();
            bo.setKnowledgeId(knowledgeId);
            bo.setQuery(evalCase.getQuestion());
            bo.setTopK(k);
            bo.setCacheable(Boolean.FALSE);
            List<KnowledgeSearchHitVo> hits = knowledgeService.search(bo);

            int firstHitRank = -1;
            double firstHitScore = 0;
            int rank = 0;
            for (KnowledgeSearchHitVo hit : hits) {
                rank++;
                if (isSatisfied(evalCase, hit)) {
                    firstHitRank = rank;
                    firstHitScore = effectiveScore(hit);
                    break;
                }
            }
            boolean hit = firstHitRank > 0;
            if (hit) {
                hitCases++;
                reciprocalSum += 1.0 / firstHitRank;
                scoreSum += firstHitScore;
                scoreCount++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", evalCase.getId());
            row.put("question", evalCase.getQuestion());
            row.put("hit", hit);
            row.put("rank", firstHitRank > 0 ? firstHitRank : null);
            row.put("score", hit ? firstHitScore : 0);
            row.put("expectedDocName", StringUtils.defaultString(evalCase.getExpectedDocName()));
            row.put("expectedKeywords", StringUtils.defaultString(evalCase.getExpectedKeywords()));
            row.put("hitFiles", hits.stream().map(KnowledgeSearchHitVo::getFileName).distinct().toList());
            caseResults.add(row);
        }

        long duration = System.currentTimeMillis() - start;
        AgentKbEvalRun run = new AgentKbEvalRun();
        run.setKnowledgeId(knowledgeId);
        run.setTopK(k);
        run.setTotalCases(cases.size());
        run.setHitCases(hitCases);
        run.setMrr(cases.isEmpty() ? 0 : reciprocalSum / cases.size());
        run.setAvgScore(scoreCount == 0 ? 0 : scoreSum / scoreCount);
        run.setDurationMs(duration);
        run.setCreateTime(new Date());
        runMapper.insert(run);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("hitRate", cases.isEmpty() ? 0 : (double) hitCases / cases.size());
        result.put("caseResults", caseResults);
        return result;
    }

    @Override
    public List<AgentKbEvalRun> listRuns(Long knowledgeId, Integer limit) {
        return runMapper.selectList(new LambdaQueryWrapper<AgentKbEvalRun>()
            .eq(AgentKbEvalRun::getKnowledgeId, knowledgeId)
            .orderByDesc(AgentKbEvalRun::getId)
            .last(limit != null && limit > 0 ? "limit " + limit : ""));
    }

    @Override
    public PageResult<AgentKbEvalRun> pageRuns(Long knowledgeId, PageQuery pageQuery) {
        Page<AgentKbEvalRun> page = runMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<AgentKbEvalRun>()
                .eq(AgentKbEvalRun::getKnowledgeId, knowledgeId)
                .orderByDesc(AgentKbEvalRun::getId));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 命中判定:期望文档名(模糊包含)与期望关键词(任一包含)同时满足
     */
    private boolean isSatisfied(AgentKbEvalCase evalCase, KnowledgeSearchHitVo hit) {
        String docName = StringUtils.defaultString(evalCase.getExpectedDocName());
        if (StringUtils.isNotBlank(docName)
            && !StringUtils.defaultString(hit.getFileName()).contains(docName)) {
            return false;
        }
        String keywords = StringUtils.defaultString(evalCase.getExpectedKeywords());
        if (StringUtils.isBlank(keywords)) {
            return true;
        }
        String content = StringUtils.defaultString(hit.getContent());
        for (String kw : keywords.split(",")) {
            if (StringUtils.isNotBlank(kw) && content.contains(kw.trim())) {
                return true;
            }
        }
        return false;
    }

    private double effectiveScore(KnowledgeSearchHitVo hit) {
        if (hit.getRerankScore() != null) {
            return hit.getRerankScore();
        }
        if (hit.getVectorScore() != null) {
            return hit.getVectorScore();
        }
        return hit.getScore() == null ? 0 : hit.getScore();
    }
}
