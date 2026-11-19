package com.forgeagent.agent.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.service.UtilityAgents;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentEvalBaseline;
import com.forgeagent.agent.domain.AgentEvalCase;
import com.forgeagent.agent.domain.AgentEvalCaseResult;
import com.forgeagent.agent.domain.AgentEvalDataset;
import com.forgeagent.agent.domain.AgentEvalRun;
import com.forgeagent.agent.mapper.AgentEvalBaselineMapper;
import com.forgeagent.agent.mapper.AgentEvalCaseMapper;
import com.forgeagent.agent.mapper.AgentEvalCaseResultMapper;
import com.forgeagent.agent.mapper.AgentEvalDatasetMapper;
import com.forgeagent.agent.mapper.AgentEvalRunMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 效果评测服务(P1 / B3)。
 *
 * <p>批量样本复用现有对话链路({@link IAgentChatService#sendAsUser}),评测产生的会话
 * 统一标记为 temp,不进正式会话列表。评分为「规则分 + 可选 LLM-as-Judge」,
 * Judge 调用失败自动回退规则分,不会让评测中断。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
public class EvaluationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CASES = 50;

    private static final String JUDGE_SYS_PROMPT = """
        你是回答质量评审员。请对给定问题与助手回答打分,只输出一个 JSON 对象,
        格式 {"score": 0-100 的整数, "reason": "不超过60字的原因"}。
        评分维度:是否切题、事实一致性、完整度、是否含明显臆造。不要输出 JSON 之外的文字。
        """;

    /** R4-4 答案层五维裁判:只在有参考答案或期望拒答的用例上跑(均分 >=4 视为通过) */
    private static final String FIVE_DIM_SYS_PROMPT = """
        你是回答质量评审员。请对给定问题与助手回答按五个维度打分,每维 1~5 整数,只输出一个 JSON 对象:
        {"correctness":1-5,"faithfulness":1-5,"completeness":1-5,"citation_correctness":1-5,"refusal_correctness":1-5,"reason":"不超过80字"}
        correctness=事实正确性;faithfulness=忠实于参考资料不臆造;completeness=完整度;
        citation_correctness=引用编号真实有效(无引用可给 3);refusal_correctness=该拒答时拒答、不该拒答时作答。
        期望拒答的用例以 refusal_correctness 为主。不要输出 JSON 之外的文字。
        """;

    private final AgentEvalDatasetMapper datasetMapper;
    private final AgentEvalCaseMapper caseMapper;
    private final AgentEvalRunMapper runMapper;
    private final AgentEvalCaseResultMapper resultMapper;
    private final AgentEvalBaselineMapper baselineMapper;
    private final AgentInfoMapper agentInfoMapper;
    private final AgentModelMapper agentModelMapper;
    private final com.forgeagent.agent.mapper.AgentSessionMapper agentSessionMapper;
    private final IAgentChatService chatService;

    public EvaluationService(AgentEvalDatasetMapper datasetMapper, AgentEvalCaseMapper caseMapper,
                             AgentEvalRunMapper runMapper, AgentEvalCaseResultMapper resultMapper,
                             AgentEvalBaselineMapper baselineMapper, AgentInfoMapper agentInfoMapper,
                             AgentModelMapper agentModelMapper,
                             com.forgeagent.agent.mapper.AgentSessionMapper agentSessionMapper,
                             IAgentChatService chatService) {
        this.datasetMapper = datasetMapper;
        this.caseMapper = caseMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.baselineMapper = baselineMapper;
        this.agentInfoMapper = agentInfoMapper;
        this.agentModelMapper = agentModelMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.chatService = chatService;
    }

    public List<com.forgeagent.agent.domain.vo.AgentEvalDatasetVo> listDatasets(Long workspaceId) {
        return datasetMapper.listWithCaseCount(workspaceId == null ? 0L : workspaceId);
    }

    public Long createDataset(com.forgeagent.agent.domain.bo.EvalDatasetCreateBo body) {
        String name = body.getName();
        if (StringUtils.isBlank(name)) {
            throw new ServiceException("评测集名称不能为空");
        }
        Long workspaceId = body.getWorkspaceId();
        if (workspaceId == null) {
            throw new ServiceException("工作空间不能为空");
        }
        AgentEvalDataset entity = new AgentEvalDataset();
        entity.setWorkspaceId(workspaceId);
        entity.setAgentId(body.getAgentId());
        entity.setName(name);
        entity.setDescription(body.getDescription());
        entity.setCreateBy(currentUserId());
        datasetMapper.insert(entity);
        return entity.getId();
    }

    public List<com.forgeagent.agent.domain.vo.AgentEvalCaseVo> listCases(Long datasetId) {
        return caseMapper.listByDataset(datasetId);
    }

    /** 批量导入样本;cases 元素含 question / expectedKeywords / referenceAnswer / expectedRefusal */
    public int addCases(com.forgeagent.agent.domain.bo.EvalCasesAddBo bo) {
        List<com.forgeagent.agent.domain.bo.EvalCasesAddBo.CaseItem> items = bo.getCases();
        if (items == null || items.isEmpty()) {
            throw new ServiceException("cases 必须是数组");
        }
        int count = 0;
        for (com.forgeagent.agent.domain.bo.EvalCasesAddBo.CaseItem row : items) {
            String question = row.getQuestion();
            if (StringUtils.isBlank(question)) {
                continue;
            }
            boolean refusal = Boolean.TRUE.equals(row.getExpectedRefusal());
            AgentEvalCase entity = new AgentEvalCase();
            entity.setDatasetId(bo.getDatasetId());
            entity.setQuestion(question);
            entity.setExpectedKeywords(row.getExpectedKeywords());
            entity.setReferenceAnswer(row.getReferenceAnswer());
            entity.setExpectedRefusal(refusal ? 1 : 0);
            caseMapper.insert(entity);
            count++;
            if (count >= MAX_CASES) {
                break;
            }
        }
        return count;
    }

    /** 同步执行一次评测(样本上限 50,避免长请求) */
    public com.forgeagent.agent.domain.vo.EvalRunOutcomeVo run(com.forgeagent.agent.domain.bo.EvalRunStartBo bo) {
        Long datasetId = bo.getDatasetId();
        Long agentId = bo.getAgentId();
        boolean judgeEnabled = Boolean.TRUE.equals(bo.getJudgeEnabled());
        // D2:待确认的回流样本(review_status='0')不参与评测,否则会稀释基线
        List<Map<String, Object>> cases = caseMapper.selectApproved(datasetId, MAX_CASES);
        if (cases.isEmpty()) {
            throw new ServiceException("评测集没有已确认的可用样本");
        }
        AgentInfo agent = agentInfoMapper.selectById(agentId);
        Long workspaceId = agent == null ? null : agent.getWorkspaceId();
        // 记录评测时刻的配置指纹,发布门禁据此判断「评测是否覆盖当前配置」
        String configHash = agent == null ? null : agent.getConfigHash();
        Long userId = currentUserId();
        AgentEvalRun runEntity = new AgentEvalRun();
        runEntity.setDatasetId(datasetId);
        runEntity.setAgentId(agentId);
        runEntity.setWorkspaceId(workspaceId);
        runEntity.setStatus("running");
        runEntity.setTotalCases(cases.size());
        runEntity.setJudgeEnabled(judgeEnabled ? "1" : "0");
        runEntity.setConfigHash(configHash);
        runEntity.setCreateBy(userId);
        runMapper.insert(runEntity);
        Long runId = runEntity.getId();

        List<Double> scores = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        int refusals = 0;
        int finished = 0;
        int totalTokens = 0;
        try {
            for (Map<String, Object> one : cases) {
                String question = text(one.get("question"));
                long start = System.currentTimeMillis();
                String answer = null;
                String error = null;
                Integer tokens = null;
                try {
                    ChatSendVo vo = chatService.sendAsUser(agentId, question, userId);
                    if (vo != null) {
                        answer = vo.getAssistantMessage() == null ? null : vo.getAssistantMessage().getContent();
                        // 评测会话不进正式会话列表
                        if (vo.getSessionId() != null) {
                            com.forgeagent.agent.domain.AgentSession sessionEntity = new com.forgeagent.agent.domain.AgentSession();
                            sessionEntity.setId(vo.getSessionId());
                            sessionEntity.setTemp(1);
                            agentSessionMapper.updateById(sessionEntity);
                        }
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    log.warn("[Evaluation] 样本执行失败 caseId={}: {}", one.get("id"), error);
                }
                long latency = System.currentTimeMillis() - start;
                boolean expectedRefusal = Integer.valueOf(1).equals(
                    ((Number) one.getOrDefault("expected_refusal", 0)).intValue());
                double rule = expectedRefusal
                    ? (isRefusal(answer) ? 100 : 20)
                    : ruleScore(answer, text(one.get("expected_keywords")));
                Double judgeScore = null;
                String judgeReason = null;
                // R4-4:五维裁判只在有参考答案或期望拒答的用例上跑,不强制全量(成本)
                boolean fiveDimQualify = StringUtils.isNotBlank(text(one.get("reference_answer"))) || expectedRefusal;
                if (judgeEnabled && fiveDimQualify && StringUtils.isNotBlank(answer) && error == null) {
                    Judge judge = llmJudge(agentId, question, answer,
                        text(one.get("reference_answer")), expectedRefusal);
                    if (judge != null) {
                        judgeScore = judge.score();
                        judgeReason = judge.reason();
                    }
                }
                double finalScore = judgeScore == null ? rule : (rule + judgeScore) / 2;
                scores.add(finalScore);
                latencies.add(latency);
                if (isRefusal(answer)) {
                    refusals++;
                }
                AgentEvalCaseResult resultEntity = new AgentEvalCaseResult();
                resultEntity.setRunId(runId);
                resultEntity.setCaseId(((Number) one.get("id")).longValue());
                resultEntity.setQuestion(question);
                resultEntity.setAnswer(answer);
                resultEntity.setScore(java.math.BigDecimal.valueOf(finalScore));
                resultEntity.setJudgeScore(judgeScore == null ? null : java.math.BigDecimal.valueOf(judgeScore));
                resultEntity.setJudgeReason(judgeReason);
                resultEntity.setLatencyMs(latency);
                resultEntity.setTotalTokens(tokens);
                resultEntity.setErrorMessage(error);
                resultMapper.insert(resultEntity);
                finished++;
                AgentEvalRun progress = new AgentEvalRun();
                progress.setId(runId);
                progress.setFinishedCases(finished);
                runMapper.updateById(progress);
            }
            double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            long p95 = percentile95(latencies);
            double refusalRate = refusals * 100.0 / cases.size();
            double successRate = (cases.size() - countErrors(runId)) * 100.0 / cases.size();
            AgentEvalRun done = new AgentEvalRun();
            done.setId(runId);
            done.setStatus("success");
            done.setAvgScore(java.math.BigDecimal.valueOf(avg));
            done.setToolSuccessRate(java.math.BigDecimal.valueOf(successRate));
            done.setRefusalRate(java.math.BigDecimal.valueOf(refusalRate));
            done.setP95LatencyMs(p95);
            done.setTotalTokens(totalTokens);
            done.setFinishTime(new java.util.Date());
            runMapper.updateById(done);
            com.forgeagent.agent.domain.vo.EvalRunOutcomeVo result = new com.forgeagent.agent.domain.vo.EvalRunOutcomeVo();
            result.setRunId(runId);
            result.setStatus("success");
            result.setAvgScore(avg);
            result.setTotalCases(cases.size());
            result.setP95LatencyMs(p95);
            result.setRefusalRate(refusalRate);
            result.setJudgeEnabled(judgeEnabled);
            return result;
        } catch (Exception e) {
            AgentEvalRun failed = new AgentEvalRun();
            failed.setId(runId);
            failed.setStatus("failed");
            failed.setErrorMessage(cut(e.getMessage(), 900));
            failed.setFinishTime(new java.util.Date());
            runMapper.updateById(failed);
            throw new ServiceException("评测执行失败: " + e.getMessage());
        }
    }

    public com.forgeagent.agent.domain.vo.EvalRunDetailVo runDetail(Long runId) {
        AgentEvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ServiceException("评测任务不存在");
        }
        com.forgeagent.agent.domain.vo.EvalRunDetailVo detail = new com.forgeagent.agent.domain.vo.EvalRunDetailVo();
        detail.setRun(MAPPER.convertValue(run, com.forgeagent.agent.domain.vo.AgentEvalRunVo.class));
        detail.setResults(runMapper.selectResults(runId));
        return detail;
    }

    public List<com.forgeagent.agent.domain.vo.AgentEvalRunVo> listRuns(Long agentId) {
        return runMapper.listByAgent(agentId);
    }

    /** 从一次成功评测生成黄金基线(单 active,旧基线自动失效) */
    public Long createBaseline(Long runId) {
        AgentEvalRun run = runMapper.selectById(runId);
        if (run == null || !"success".equals(run.getStatus())) {
            throw new ServiceException("只有成功的评测任务可以设为基线");
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("avgScore", run.getAvgScore());
        metrics.put("toolSuccessRate", run.getToolSuccessRate());
        metrics.put("refusalRate", run.getRefusalRate());
        metrics.put("p95LatencyMs", run.getP95LatencyMs());
        // 旧基线失效(MP updateById 忽略 null,用 LambdaUpdateWrapper 明确置值)
        baselineMapper.update(null, Wrappers.<AgentEvalBaseline>lambdaUpdate()
            .eq(AgentEvalBaseline::getAgentId, run.getAgentId())
            .set(AgentEvalBaseline::getActive, "0"));
        AgentEvalBaseline baseline = new AgentEvalBaseline();
        baseline.setAgentId(run.getAgentId());
        baseline.setDatasetId(run.getDatasetId());
        baseline.setRunId(runId);
        baseline.setMetricsJson(JsonUtils.toJsonString(metrics));
        baseline.setActive("1");
        baseline.setCreateBy(currentUserId());
        baselineMapper.insert(baseline);
        return baseline.getId();
    }

    public Map<String, Object> activeBaseline(Long agentId) {
        List<Map<String, Object>> rows = baselineMapper.selectActive(agentId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private record Judge(Double score, String reason) {
    }

    /** LLM-as-Judge;模型不可用或输出非法时返回 null,由规则分兜底 */
    private Judge llmJudge(Long agentId, String question, String answer, String reference, boolean expectedRefusal) {
        try {
            AgentInfo agent = agentInfoMapper.selectById(agentId);
            if (agent == null || agent.getModelId() == null) {
                return null;
            }
            AgentModel model = agentModelMapper.selectById(agent.getModelId());
            if (model == null || text(model.getBaseUrl()) == null || text(model.getApiKey()) == null) {
                return null;
            }
            String prompt = "问题:" + question + "\n助手回答:" + cut(answer, 2000)
                + "\n参考答案:" + (reference == null ? "无" : cut(reference, 1000))
                + (expectedRefusal ? "\n(本用例期望模型拒答:知识库无相关资料时应明确告知)" : "");
            Msg result = UtilityAgents.build("eval-judge",
                expectedRefusal || StringUtils.isNotBlank(reference) ? FIVE_DIM_SYS_PROMPT : JUDGE_SYS_PROMPT, model)
                .call(List.of(Msg.builder().name("user").role(MsgRole.USER).textContent(prompt).build()))
                .block(Duration.ofSeconds(60));
            if (result == null || StringUtils.isBlank(result.getTextContent())) {
                return null;
            }
            String raw = result.getTextContent();
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            Map<String, Object> parsed = MAPPER.readValue(raw.substring(start, end + 1),
                new TypeReference<>() {
                });
            // 五维裁判:均分 1~5 → 换算 0~100,维度 JSON 存 judge_reason 供回归对比
            if (parsed.containsKey("correctness")) {
                double sum = 0;
                int dims = 0;
                for (String dim : List.of("correctness", "faithfulness", "completeness",
                    "citation_correctness", "refusal_correctness")) {
                    Object v = parsed.get(dim);
                    if (v instanceof Number n) {
                        sum += Math.max(1, Math.min(5, n.doubleValue()));
                        dims++;
                    }
                }
                if (dims > 0) {
                    Map<String, Object> compact = new LinkedHashMap<>();
                    compact.put("dims", parsed);
                    compact.put("pass", sum / dims >= 4.0);
                    return new Judge(sum / dims * 20, cut(MAPPER.writeValueAsString(compact), 400));
                }
            }
            double score = Double.parseDouble(String.valueOf(parsed.getOrDefault("score", 0)));
            return new Judge(Math.max(0, Math.min(100, score)),
                cut(text(parsed.get("reason")), 200));
        } catch (Exception e) {
            log.warn("[Evaluation] Judge 调用失败,回退规则评分: {}", e.getMessage());
            return null;
        }
    }

    private double ruleScore(String answer, String keywords) {
        if (StringUtils.isBlank(answer)) {
            return 0;
        }
        double score = 40;
        if (answer.length() >= 20) {
            score += 20;
        }
        if (StringUtils.isNotBlank(keywords)) {
            String[] parts = keywords.split("[,，]");
            long hit = java.util.Arrays.stream(parts)
                .map(String::trim).filter(StringUtils::isNotBlank)
                .filter(k -> answer.contains(k)).count();
            score += parts.length == 0 ? 0 : 40.0 * hit / parts.length;
        } else {
            score += 20;
        }
        return Math.min(100, score);
    }

    private boolean isRefusal(String answer) {
        if (StringUtils.isBlank(answer)) {
            return true;
        }
        return answer.contains("无法回答") || answer.contains("暂无相关资料")
            || answer.contains("不知道") || answer.contains("资料不足");
    }

    private int countErrors(Long runId) {
        return resultMapper.selectCount(Wrappers.<AgentEvalCaseResult>lambdaQuery()
            .eq(AgentEvalCaseResult::getRunId, runId)
            .isNotNull(AgentEvalCaseResult::getErrorMessage)).intValue();
    }

    private static long percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
