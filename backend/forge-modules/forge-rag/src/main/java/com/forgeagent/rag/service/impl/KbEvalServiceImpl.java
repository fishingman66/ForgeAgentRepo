package com.forgeagent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKbEvalCase;
import com.forgeagent.rag.domain.AgentKbEvalRun;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKbEvalCaseMapper;
import com.forgeagent.rag.mapper.AgentKbEvalRunMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.rag.service.KbEvalService;
import com.forgeagent.rag.service.eval.CorpusFingerprints;
import com.forgeagent.rag.service.eval.EvalEvidence;
import com.forgeagent.rag.service.eval.EvalMetricsCalculator;
import com.forgeagent.rag.service.eval.GateJudgeCore;
import com.forgeagent.rag.service.eval.KbDocumentGenerationEvent;
import com.forgeagent.rag.service.eval.SpanHitJudge;
import com.forgeagent.rag.service.retrieve.SearchOutcome;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库检索评测服务实现(R3 升级)
 *
 * <p>判定口径:</p>
 * <ul>
 *   <li>带证据标注的用例:span 级走 {@link SpanHitJudge} 区间并集覆盖(阈值 0.5,
 *       rank 前缀累积);文档级(span 全空)只比 documentId 精确相等。</li>
 *   <li>旧用例(无 evidences):保留 legacy 口径(期望文档名模糊包含 + 关键词任一包含),
 *       结果标 legacy=true,与新口径不混算(分组独立)。</li>
 *   <li>EVIDENCE_STALE / DEPRECATED 用例不入指标,分别计数;检索降级用例计数并标记。</li>
 * </ul>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KbEvalServiceImpl implements KbEvalService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_STALE = "EVIDENCE_STALE";
    private static final String STATUS_DEPRECATED = "DEPRECATED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentKbEvalCaseMapper caseMapper;
    private final AgentKbEvalRunMapper runMapper;
    private final AgentKnowledgeDocumentMapper documentMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final IAgentKnowledgeService knowledgeService;

    @Override
    public Long saveCase(AgentKbEvalCase evalCase) {
        if (evalCase.getKnowledgeId() == null || StringUtils.isBlank(evalCase.getQuestion())) {
            throw new ServiceException("知识库ID与评测问题不能为空");
        }
        EvalEvidence.validate(evalCase.getEvidences());
        if (StringUtils.isBlank(evalCase.getStatus())) {
            evalCase.setStatus(STATUS_ACTIVE);
        }
        if (StringUtils.isBlank(evalCase.getSource())) {
            evalCase.setSource("MANUAL");
        }
        if (evalCase.getCreateTime() == null) {
            evalCase.setCreateTime(new Date());
        }
        evalCase.setUpdateTime(new Date());
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

        int staleCases = 0;
        int deprecatedCases = 0;
        int degradedCases = 0;
        double scoreSum = 0;
        int scoreCount = 0;
        List<EvalMetricsCalculator.CaseOutcome> outcomes = new ArrayList<>();
        List<Map<String, Object>> caseResults = new ArrayList<>(cases.size());

        for (AgentKbEvalCase evalCase : cases) {
            String status = StringUtils.defaultIfBlank(evalCase.getStatus(), STATUS_ACTIVE);
            if (STATUS_DEPRECATED.equals(status)) {
                deprecatedCases++;
                caseResults.add(caseRow(evalCase, status, false, null, 0, List.of(), false));
                continue;
            }
            if (STATUS_STALE.equals(status)) {
                staleCases++;
                caseResults.add(caseRow(evalCase, status, false, null, 0, List.of(), false));
                continue;
            }

            KnowledgeSearchBo bo = new KnowledgeSearchBo();
            bo.setKnowledgeId(knowledgeId);
            bo.setQuery(evalCase.getQuestion());
            bo.setTopK(k);
            bo.setCacheable(Boolean.FALSE);
            SearchOutcome outcome = knowledgeService.searchOutcome(bo);
            List<KnowledgeSearchHitVo> hits = outcome.hits();
            boolean degraded = outcome.isDegraded();
            if (degraded) {
                degradedCases++;
            }

            List<EvalEvidence> evidences = EvalEvidence.parse(evalCase.getEvidences());
            CaseJudge judge = evidences.isEmpty()
                ? judgeLegacy(evalCase, hits, k)
                : judgeEvidence(evidences, hits, k);
            if (judge.hit()) {
                scoreSum += judge.firstScore();
                scoreCount++;
            }
            outcomes.add(new EvalMetricsCalculator.CaseOutcome(
                String.valueOf(evalCase.getId()), judge.group(), judge.hit(), judge.hitRank(),
                judge.evidenceCount(), judge.evidenceHits(), judge.relevantCount(), judge.ndcg()));
            caseResults.add(caseRow(evalCase, status, judge.hit(),
                judge.hitRank() > 0 ? judge.hitRank() : null, judge.firstScore(),
                judge.evidenceCoverages(), degraded));
        }

        EvalMetricsCalculator.Metrics metrics = EvalMetricsCalculator.compute(outcomes, k);
        EvalMetricsCalculator.GroupMetrics overall = metrics.overall();
        int hitCasesCount = (int) outcomes.stream().filter(EvalMetricsCalculator.CaseOutcome::hit).count();

        long duration = System.currentTimeMillis() - start;
        AgentKnowledge kb = knowledgeMapper.selectById(knowledgeId);
        AgentKbEvalRun run = new AgentKbEvalRun();
        run.setKnowledgeId(knowledgeId);
        run.setTopK(k);
        run.setTotalCases(cases.size());
        run.setHitCases(hitCasesCount);
        run.setHitRate(overall != null ? overall.hitRate() : 0);
        run.setRecallAtK(overall != null ? overall.recall() : 0);
        run.setPrecisionAtK(overall != null ? overall.precision() : 0);
        run.setMrr(overall != null ? overall.mrr() : 0);
        run.setNdcgAtK(overall != null ? overall.ndcg() : 0);
        run.setMetricsJson(toJson(metrics));
        run.setAvgScore(scoreCount == 0 ? 0 : scoreSum / scoreCount);
        run.setDurationMs(duration);
        run.setEffectiveCases(outcomes.size());
        run.setStaleCases(staleCases);
        run.setDegradedCases(degradedCases);
        run.setCorpusFingerprint(corpusFingerprint(knowledgeId));
        run.setConfigSnapshot(configSnapshot(kb));
        run.setDatasetRevision(datasetRevisionOf(cases));
        run.setCaseResultsJson(toJson(caseOutcomeRows(outcomes)));
        run.setCreateTime(new Date());
        runMapper.insert(run);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("hitRate", run.getHitRate());
        result.put("metrics", metrics);
        result.put("deprecatedCases", deprecatedCases);
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
     * R3-1 自动打标:文档换代/删除后,对引用该文档的用例重跑覆盖判定,
     * 不达标标 EVIDENCE_STALE(判定阈值与 R3-2 一致:"不再算命中"与"不可再信任"是同一件事);
     * 已过期但换回可达的用例恢复 ACTIVE。
     */
    @EventListener
    public void onDocumentGeneration(KbDocumentGenerationEvent event) {
        try {
            refreshEvidenceStatus(event.knowledgeId(), event.documentId());
        } catch (Exception e) {
            log.warn("[知识库评测] 文档 {} 证据状态刷新失败(不阻断主流程): {}", event.documentId(), e.getMessage());
        }
    }

    private void refreshEvidenceStatus(Long knowledgeId, Long documentId) {
        AgentKnowledgeDocument doc = documentMapper.selectById(documentId);
        List<String> candidates = new ArrayList<>();
        if (doc != null) {
            List<AgentKbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AgentKbChunk>()
                .eq(AgentKbChunk::getDocumentId, documentId)
                .eq(AgentKbChunk::getGeneration, doc.getActiveGeneration())
                .ne(AgentKbChunk::getChunkRole, "p")
                .eq(AgentKbChunk::getEnabled, 1));
            chunks.forEach(c -> candidates.add(StringUtils.defaultString(c.getContent())));
        }

        List<AgentKbEvalCase> cases = caseMapper.selectList(new LambdaQueryWrapper<AgentKbEvalCase>()
            .eq(AgentKbEvalCase::getKnowledgeId, knowledgeId)
            .in(AgentKbEvalCase::getStatus, List.of(STATUS_ACTIVE, STATUS_STALE)));
        int changed = 0;
        for (AgentKbEvalCase evalCase : cases) {
            List<EvalEvidence> evidences;
            try {
                evidences = EvalEvidence.parse(evalCase.getEvidences()).stream()
                    .filter(e -> e.refersDocument(documentId)).toList();
            } catch (Exception e) {
                continue;
            }
            if (evidences.isEmpty()) {
                continue;
            }
            boolean pass;
            if (candidates.isEmpty()) {
                pass = false;
            } else {
                pass = true;
                for (EvalEvidence ev : evidences) {
                    if (ev.span() == null) {
                        continue;
                    }
                    if (SpanHitJudge.coverage(candidates, ev.span()) < SpanHitJudge.HIT_COVERAGE_THRESHOLD) {
                        pass = false;
                        break;
                    }
                }
            }
            String target = pass ? STATUS_ACTIVE : STATUS_STALE;
            if (!target.equals(StringUtils.defaultIfBlank(evalCase.getStatus(), STATUS_ACTIVE))) {
                caseMapper.update(null, new LambdaUpdateWrapper<AgentKbEvalCase>()
                    .eq(AgentKbEvalCase::getId, evalCase.getId())
                    .set(AgentKbEvalCase::getStatus, target)
                    .set(AgentKbEvalCase::getUpdateTime, new Date()));
                changed++;
            }
        }
        if (changed > 0) {
            log.info("[知识库评测] 文档 {} 换代/删除后刷新证据状态: {} 个用例变更", documentId, changed);
        }
    }

    /**
     * 单用例判定产物
     */
    private record CaseJudge(String group, boolean hit, int hitRank, double firstScore,
                             int evidenceCount, int evidenceHits, int relevantCount,
                             double ndcg, List<Double> evidenceCoverages) {
    }

    /**
     * legacy 口径:期望文档名(模糊包含) + 期望关键词(任一包含),命中取首个满足条目
     */
    private CaseJudge judgeLegacy(AgentKbEvalCase evalCase, List<KnowledgeSearchHitVo> hits, int k) {
        int firstHitRank = -1;
        double firstHitScore = 0;
        int relevant = 0;
        List<Integer> relBinary = new ArrayList<>();
        for (KnowledgeSearchHitVo hit : hits) {
            boolean satisfied = isSatisfied(evalCase, hit);
            relBinary.add(satisfied ? 1 : 0);
            if (satisfied) {
                relevant++;
                if (firstHitRank < 0) {
                    firstHitRank = relBinary.size();
                    firstHitScore = effectiveScore(hit);
                }
            }
        }
        boolean hit = firstHitRank > 0;
        double ndcg = EvalMetricsCalculator.ndcgOfCase(relBinary, 1, k);
        return new CaseJudge("legacy", hit, firstHitRank, firstHitScore, 1, hit ? 1 : 0, relevant,
            ndcg, List.of());
    }

    /**
     * 证据口径:span 级走覆盖判定(rank 前缀累积),文档级只比 docId 精确相等
     */
    private CaseJudge judgeEvidence(List<EvalEvidence> evidences, List<KnowledgeSearchHitVo> hits, int k) {
        List<String> spans = evidences.stream().filter(e -> e.span() != null).map(EvalEvidence::span).toList();
        Set<Long> docIds = new HashSet<>(evidences.stream().map(EvalEvidence::documentId).toList());
        boolean spanMode = !spans.isEmpty();

        int[] evidenceRanks = new int[evidences.size()];
        double[] evidenceCoverage = new double[evidences.size()];
        List<String> prefixContents = new ArrayList<>();
        List<Integer> relBinary = new ArrayList<>();
        int relevant = 0;

        for (KnowledgeSearchHitVo hit : hits) {
            prefixContents.add(StringUtils.defaultString(hit.getContent()));
            boolean relevantHit = false;
            if (spanMode) {
                relevantHit = SpanHitJudge.overlapsAny(hit.getContent(), spans);
            }
            if (!relevantHit && hit.getDocumentId() != null && docIds.contains(hit.getDocumentId())) {
                relevantHit = true;
            }
            relBinary.add(relevantHit ? 1 : 0);
            if (relevantHit) {
                relevant++;
            }
            for (int i = 0; i < evidences.size(); i++) {
                if (evidenceRanks[i] > 0) {
                    continue;
                }
                EvalEvidence ev = evidences.get(i);
                if (ev.span() != null) {
                    double cov = SpanHitJudge.coverage(prefixContents, ev.span());
                    evidenceCoverage[i] = cov;
                    if (cov >= SpanHitJudge.HIT_COVERAGE_THRESHOLD) {
                        evidenceRanks[i] = prefixContents.size();
                    }
                } else if (hit.getDocumentId() != null && hit.getDocumentId().equals(ev.documentId())) {
                    evidenceRanks[i] = prefixContents.size();
                    evidenceCoverage[i] = 1.0;
                }
            }
        }

        int hitCount = 0;
        int hitRank = -1;
        for (int rank : evidenceRanks) {
            if (rank > 0) {
                hitCount++;
                hitRank = Math.max(hitRank, rank);
            }
        }
        boolean hit = hitCount == evidences.size() && !hits.isEmpty();
        if (!hit) {
            hitRank = -1;
        }
        double firstScore = hit && hitRank <= hits.size()
            ? effectiveScore(hits.get(hitRank - 1)) : 0;
        String group = spanMode ? "span" : "document";
        List<Double> coverages = new ArrayList<>();
        for (double c : evidenceCoverage) {
            coverages.add(c);
        }
        double ndcg = EvalMetricsCalculator.ndcgOfCase(relBinary, evidences.size(), k);
        return new CaseJudge(group, hit, hitRank, firstScore, evidences.size(), hitCount,
            relevant, ndcg, coverages);
    }

    private Map<String, Object> caseRow(AgentKbEvalCase evalCase, String status, boolean hit,
                                        Integer rank, double score, List<Double> coverages,
                                        boolean degraded) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", evalCase.getId());
        row.put("question", evalCase.getQuestion());
        row.put("status", status);
        row.put("legacy", StringUtils.isBlank(evalCase.getEvidences()));
        row.put("hit", hit);
        row.put("rank", rank);
        row.put("score", score);
        row.put("evidenceCoverages", coverages);
        row.put("degraded", degraded);
        row.put("expectedDocName", StringUtils.defaultString(evalCase.getExpectedDocName()));
        row.put("expectedKeywords", StringUtils.defaultString(evalCase.getExpectedKeywords()));
        return row;
    }

    @Override
    public String corpusFingerprint(Long knowledgeId) {
        List<AgentKnowledgeDocument> docs = documentMapper.selectList(
            new LambdaQueryWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getKnowledgeId, knowledgeId)
                .eq(AgentKnowledgeDocument::getStatus, "1"));
        AgentKnowledge kb = knowledgeMapper.selectById(knowledgeId);
        List<CorpusFingerprints> pairs = docs.stream()
            .map(d -> new CorpusFingerprints(d.getId(),
                d.getActiveGeneration() == null ? 0L : d.getActiveGeneration())).toList();
        return CorpusFingerprints.fingerprint(pairs, kb == null ? null : kb.getEmbeddingModel(),
            kb == null ? null : kb.getVectorDim(), kb == null ? null : kb.getChunkFingerprint());
    }

    @Override
    public Map<String, Object> compare(Long knowledgeId, Long baselineRunId, Long candidateRunId) {
        AgentKbEvalRun baseline = runMapper.selectById(baselineRunId);
        AgentKbEvalRun candidate = runMapper.selectById(candidateRunId);
        if (baseline == null || candidate == null) {
            throw new ServiceException("评测运行记录不存在");
        }
        if (!knowledgeId.equals(baseline.getKnowledgeId()) || !knowledgeId.equals(candidate.getKnowledgeId())) {
            throw new ServiceException("两次运行不属于同一知识库");
        }
        List<CaseSnapshot> baselineCases = parseSnapshots(baseline.getCaseResultsJson());
        List<CaseSnapshot> candidateCases = parseSnapshots(candidate.getCaseResultsJson());
        Set<Long> intersection = new HashSet<>(baselineCases.stream().map(CaseSnapshot::caseId).toList());
        intersection.retainAll(candidateCases.stream().map(CaseSnapshot::caseId).toList());

        EvalMetricsCalculator.Metrics baselineMetrics = EvalMetricsCalculator.compute(
            filterSnapshots(baselineCases, intersection), candidate.getTopK() == null ? 5 : candidate.getTopK());
        EvalMetricsCalculator.Metrics candidateMetrics = EvalMetricsCalculator.compute(
            filterSnapshots(candidateCases, intersection), candidate.getTopK() == null ? 5 : candidate.getTopK());

        GateJudgeCore.Verdict verdict = GateJudgeCore.judge(
            new GateJudgeCore.Side(
                baselineMetrics.overall() != null ? baselineMetrics.overall().hitRate() : 0,
                baselineMetrics.overall() != null ? baselineMetrics.overall().recall() : 0,
                intersection.size(), baseline.getStaleCases() == null ? 0 : baseline.getStaleCases(),
                baseline.getDegradedCases() == null ? 0 : baseline.getDegradedCases(),
                baseline.getCorpusFingerprint(), baseline.getDatasetRevision()),
            new GateJudgeCore.Side(
                candidateMetrics.overall() != null ? candidateMetrics.overall().hitRate() : 0,
                candidateMetrics.overall() != null ? candidateMetrics.overall().recall() : 0,
                intersection.size(), candidate.getStaleCases() == null ? 0 : candidate.getStaleCases(),
                candidate.getDegradedCases() == null ? 0 : candidate.getDegradedCases(),
                candidate.getCorpusFingerprint(), candidate.getDatasetRevision()));

        runMapper.update(null, new LambdaUpdateWrapper<AgentKbEvalRun>()
            .eq(AgentKbEvalRun::getId, candidateRunId)
            .set(AgentKbEvalRun::getGateState, verdict.state().name())
            .set(AgentKbEvalRun::getGateReason, verdict.reason().name()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", verdict.state().name());
        result.put("reason", verdict.reason().name());
        result.put("epsilon", verdict.epsilon());
        result.put("delta", verdict.delta());
        result.put("baselineHitRate", verdict.baselineHitRate());
        result.put("candidateHitRate", verdict.candidateHitRate());
        result.put("intersectionCases", intersection.size());
        result.put("description", GateJudgeCore.describe(verdict));
        return result;
    }

    @Override
    public Map<String, Object> gateCheck(Long knowledgeId) {
        List<AgentKbEvalRun> runs = runMapper.selectList(new LambdaQueryWrapper<AgentKbEvalRun>()
            .eq(AgentKbEvalRun::getKnowledgeId, knowledgeId)
            .orderByDesc(AgentKbEvalRun::getId)
            .last("limit 2"));
        GateJudgeCore.Verdict verdict;
        if (runs.isEmpty()) {
            verdict = new GateJudgeCore.Verdict(GateJudgeCore.State.LOG_ONLY,
                GateJudgeCore.Reason.INSUFFICIENT_CASES, 0, 0, 0, 0);
        } else {
            AgentKbEvalRun candidate = runs.get(0);
            GateJudgeCore.Side candidateSide = sideOf(candidate);
            GateJudgeCore.Side baselineSide = runs.size() > 1 ? sideOf(runs.get(1)) : null;
            verdict = GateJudgeCore.judge(baselineSide, candidateSide);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", verdict.state().name());
        result.put("reason", verdict.reason().name());
        result.put("epsilon", verdict.epsilon());
        result.put("delta", verdict.delta());
        result.put("description", GateJudgeCore.describe(verdict));
        return result;
    }

    private GateJudgeCore.Side sideOf(AgentKbEvalRun run) {
        return new GateJudgeCore.Side(
            run.getHitRate() == null ? 0 : run.getHitRate(),
            run.getRecallAtK() == null ? 0 : run.getRecallAtK(),
            run.getEffectiveCases() == null ? 0 : run.getEffectiveCases(),
            run.getStaleCases() == null ? 0 : run.getStaleCases(),
            run.getDegradedCases() == null ? 0 : run.getDegradedCases(),
            run.getCorpusFingerprint(), run.getDatasetRevision());
    }

    /**
     * 逐用例判定快照(落库供交集重算)
     */
    private record CaseSnapshot(Long caseId, String group, boolean hit, int hitRank,
                                int evidenceCount, int evidenceHits, int relevantCount, double ndcg) {
    }

    @SuppressWarnings("unchecked")
    private List<CaseSnapshot> parseSnapshots(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(json, List.class);
            List<CaseSnapshot> list = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                list.add(new CaseSnapshot(((Number) row.get("caseId")).longValue(),
                    (String) row.get("group"), Boolean.TRUE.equals(row.get("hit")),
                    ((Number) row.getOrDefault("hitRank", -1)).intValue(),
                    ((Number) row.getOrDefault("evidenceCount", 0)).intValue(),
                    ((Number) row.getOrDefault("evidenceHits", 0)).intValue(),
                    ((Number) row.getOrDefault("relevantCount", 0)).intValue(),
                    row.get("ndcg") instanceof Number n ? n.doubleValue() : 0));
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<EvalMetricsCalculator.CaseOutcome> filterSnapshots(List<CaseSnapshot> snapshots,
                                                                    Set<Long> intersection) {
        return snapshots.stream()
            .filter(s -> intersection.contains(s.caseId()))
            .map(s -> new EvalMetricsCalculator.CaseOutcome(String.valueOf(s.caseId()), s.group(),
                s.hit(), s.hitRank(), s.evidenceCount(), s.evidenceHits(), s.relevantCount(), s.ndcg()))
            .toList();
    }

    private List<Map<String, Object>> caseOutcomeRows(List<EvalMetricsCalculator.CaseOutcome> outcomes) {
        List<Map<String, Object>> rows = new ArrayList<>(outcomes.size());
        for (EvalMetricsCalculator.CaseOutcome o : outcomes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", Long.valueOf(o.caseKey()));
            row.put("group", o.group());
            row.put("hit", o.hit());
            row.put("hitRank", o.hitRank());
            row.put("evidenceCount", o.evidenceCount());
            row.put("evidenceHits", o.evidenceHits());
            row.put("relevantCount", o.relevantCount());
            row.put("ndcg", o.ndcg());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 配置快照:评测当时的关键检索/分块参数,供事后归因
     */
    private String configSnapshot(AgentKnowledge kb) {
        if (kb == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("embeddingModel", kb.getEmbeddingModel());
        snapshot.put("vectorDim", kb.getVectorDim());
        snapshot.put("rerankModel", kb.getRerankModel());
        snapshot.put("chunkUnit", kb.getChunkUnit());
        snapshot.put("splitStrategy", kb.getSplitStrategy());
        snapshot.put("chunkSize", kb.getChunkSize());
        snapshot.put("chunkOverlap", kb.getChunkOverlap());
        snapshot.put("parentChunkEnabled", kb.getParentChunkEnabled());
        snapshot.put("parentChunkSize", kb.getParentChunkSize());
        snapshot.put("chunkFingerprint", kb.getChunkFingerprint());
        return toJson(snapshot);
    }

    /**
     * 用例集修订号:全部 ACTIVE 用例同版取该值,否则拼接去重后的列表
     */
    private String datasetRevisionOf(List<AgentKbEvalCase> cases) {
        Set<String> revisions = new HashSet<>();
        for (AgentKbEvalCase c : cases) {
            if (StringUtils.isNotBlank(c.getDatasetRevision())) {
                revisions.add(c.getDatasetRevision().trim());
            }
        }
        if (revisions.isEmpty()) {
            return "";
        }
        List<String> sorted = revisions.stream().sorted().toList();
        return sorted.size() == 1 ? sorted.get(0) : String.join(",", sorted);
    }

    /**
     * legacy 命中判定:期望文档名(模糊包含)与期望关键词(任一包含)同时满足
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

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
