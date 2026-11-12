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
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private final IAgentChatService chatService;

    public EvaluationService(JdbcTemplate jdbc, IAgentChatService chatService) {
        this.jdbc = jdbc;
        this.chatService = chatService;
    }

    public List<Map<String, Object>> listDatasets(Long workspaceId) {
        return jdbc.queryForList("""
                SELECT d.id,d.workspace_id,d.agent_id,d.name,d.description,d.create_time,
                  (SELECT COUNT(*) FROM agent_eval_case c WHERE c.dataset_id=d.id) AS case_count
                FROM agent_eval_dataset d WHERE d.del_flag=0 AND (d.workspace_id=? OR d.workspace_id=0)
                ORDER BY d.id DESC
                """, workspaceId == null ? 0L : workspaceId);
    }

    public Long createDataset(Map<String, Object> body) {
        String name = text(body.get("name"));
        if (StringUtils.isBlank(name)) {
            throw new ServiceException("评测集名称不能为空");
        }
        Long workspaceId = longOf(body.get("workspaceId"));
        if (workspaceId == null) {
            throw new ServiceException("工作空间不能为空");
        }
        jdbc.update("""
                INSERT INTO agent_eval_dataset (workspace_id,agent_id,name,description,create_by,create_time)
                VALUES (?,?,?,?,?,NOW())
                """, workspaceId, longOf(body.get("agentId")), name,
            text(body.get("description")), currentUserId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Map<String, Object>> listCases(Long datasetId) {
        return jdbc.queryForList("""
                SELECT id,dataset_id,question,expected_keywords,reference_answer,expected_refusal,create_time
                FROM agent_eval_case WHERE dataset_id=? ORDER BY id
                """, datasetId);
    }

    /** 批量导入样本;body.cases 为数组,元素含 question / expectedKeywords / referenceAnswer / expectedRefusal */
    public int addCases(Long datasetId, Object casesRaw) {
        if (!(casesRaw instanceof List<?> cases)) {
            throw new ServiceException("cases 必须是数组");
        }
        int count = 0;
        for (Object item : cases) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String question = text(row.get("question"));
            if (StringUtils.isBlank(question)) {
                continue;
            }
            boolean refusal = Boolean.TRUE.equals(row.get("expectedRefusal"))
                || "1".equals(text(row.get("expectedRefusal")))
                || "true".equalsIgnoreCase(text(row.get("expectedRefusal")));
            jdbc.update("""
                    INSERT INTO agent_eval_case (dataset_id,question,expected_keywords,reference_answer,expected_refusal,create_time)
                    VALUES (?,?,?,?,?,NOW())
                    """, datasetId, question, text(row.get("expectedKeywords")),
                text(row.get("referenceAnswer")), refusal ? 1 : 0);
            count++;
            if (count >= MAX_CASES) {
                break;
            }
        }
        return count;
    }

    /** 同步执行一次评测(样本上限 50,避免长请求) */
    public Map<String, Object> run(Long datasetId, Long agentId, boolean judgeEnabled) {
        List<Map<String, Object>> cases = jdbc.queryForList("""
                SELECT id,question,expected_keywords,reference_answer,expected_refusal FROM agent_eval_case
                WHERE dataset_id=? ORDER BY id LIMIT ?
                """, datasetId, MAX_CASES);
        if (cases.isEmpty()) {
            throw new ServiceException("评测集没有可用样本");
        }
        List<Long> workspace = jdbc.queryForList(
            "SELECT workspace_id FROM agent_info WHERE id=?", Long.class, agentId);
        Long workspaceId = workspace.isEmpty() ? null : workspace.getFirst();
        Long userId = currentUserId();
        jdbc.update("""
                INSERT INTO agent_eval_run
                  (dataset_id,agent_id,workspace_id,status,total_cases,judge_enabled,create_by,create_time)
                VALUES (?,?,?,'running',?,?,?,NOW())
                """, datasetId, agentId, workspaceId, cases.size(),
            judgeEnabled ? "1" : "0", userId);
        Long runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

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
                            jdbc.update("UPDATE agent_session SET temp=1 WHERE id=?", vo.getSessionId());
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
                jdbc.update("""
                        INSERT INTO agent_eval_case_result
                          (run_id,case_id,question,answer,score,judge_score,judge_reason,latency_ms,total_tokens,error_message,create_time)
                        VALUES (?,?,?,?,?,?,?,?,?,?,NOW())
                        """, runId, one.get("id"), question, answer, finalScore,
                    judgeScore, judgeReason, latency, tokens, error);
                finished++;
                jdbc.update("UPDATE agent_eval_run SET finished_cases=? WHERE id=?", finished, runId);
            }
            double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            long p95 = percentile95(latencies);
            double refusalRate = refusals * 100.0 / cases.size();
            double successRate = (cases.size() - countErrors(runId)) * 100.0 / cases.size();
            jdbc.update("""
                    UPDATE agent_eval_run SET status='success',avg_score=?,tool_success_rate=?,
                      refusal_rate=?,p95_latency_ms=?,total_tokens=?,finish_time=NOW() WHERE id=?
                    """, avg, successRate, refusalRate, p95, totalTokens, runId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", runId);
            result.put("status", "success");
            result.put("avgScore", avg);
            result.put("totalCases", cases.size());
            result.put("p95LatencyMs", p95);
            result.put("refusalRate", refusalRate);
            result.put("judgeEnabled", judgeEnabled);
            return result;
        } catch (Exception e) {
            jdbc.update("UPDATE agent_eval_run SET status='failed',error_message=?,finish_time=NOW() WHERE id=?",
                cut(e.getMessage(), 900), runId);
            throw new ServiceException("评测执行失败: " + e.getMessage());
        }
    }

    public Map<String, Object> runDetail(Long runId) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT * FROM agent_eval_run WHERE id=?", runId);
        if (runs.isEmpty()) {
            throw new ServiceException("评测任务不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", runs.getFirst());
        result.put("results", jdbc.queryForList("""
                SELECT id,case_id,question,answer,score,judge_score,judge_reason,latency_ms,error_message
                FROM agent_eval_case_result WHERE run_id=? ORDER BY id
                """, runId));
        return result;
    }

    public List<Map<String, Object>> listRuns(Long agentId) {
        return jdbc.queryForList("""
                SELECT id,dataset_id,agent_id,status,total_cases,finished_cases,avg_score,
                  tool_success_rate,refusal_rate,p95_latency_ms,judge_enabled,create_time,finish_time
                FROM agent_eval_run WHERE agent_id=? ORDER BY id DESC LIMIT 50
                """, agentId);
    }

    /** 从一次成功评测生成黄金基线(单 active,旧基线自动失效) */
    public Long createBaseline(Long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM agent_eval_run WHERE id=? AND status='success'", runId);
        if (rows.isEmpty()) {
            throw new ServiceException("只有成功的评测任务可以设为基线");
        }
        Map<String, Object> run = rows.getFirst();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("avgScore", run.get("avg_score"));
        metrics.put("toolSuccessRate", run.get("tool_success_rate"));
        metrics.put("refusalRate", run.get("refusal_rate"));
        metrics.put("p95LatencyMs", run.get("p95_latency_ms"));
        jdbc.update("UPDATE agent_eval_baseline SET active='0' WHERE agent_id=?", run.get("agent_id"));
        jdbc.update("""
                INSERT INTO agent_eval_baseline (agent_id,dataset_id,run_id,metrics_json,active,create_by,create_time)
                VALUES (?,?,?,?,'1',?,NOW())
                """, run.get("agent_id"), run.get("dataset_id"), runId,
            JsonUtils.toJsonString(metrics), currentUserId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Map<String, Object> activeBaseline(Long agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM agent_eval_baseline WHERE agent_id=? AND active='1' ORDER BY id DESC LIMIT 1
                """, agentId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private record Judge(Double score, String reason) {
    }

    /** LLM-as-Judge;模型不可用或输出非法时返回 null,由规则分兜底 */
    private Judge llmJudge(Long agentId, String question, String answer, String reference, boolean expectedRefusal) {
        try {
            List<Map<String, Object>> models = jdbc.queryForList("""
                    SELECT m.base_url,m.api_key,m.model_name FROM agent_model m
                    JOIN agent_info a ON a.model_id=m.id WHERE a.id=? LIMIT 1
                    """, agentId);
            if (models.isEmpty()) {
                return null;
            }
            Map<String, Object> cfg = models.getFirst();
            if (text(cfg.get("base_url")) == null || text(cfg.get("api_key")) == null) {
                return null;
            }
            AgentModel model = new AgentModel();
            model.setBaseUrl(text(cfg.get("base_url")));
            model.setApiKey(text(cfg.get("api_key")));
            model.setModelName(text(cfg.get("model_name")));
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
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM agent_eval_case_result WHERE run_id=? AND error_message IS NOT NULL",
            Integer.class, runId);
        return count == null ? 0 : count;
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
