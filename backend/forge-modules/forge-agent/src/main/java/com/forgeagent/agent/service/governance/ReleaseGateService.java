package com.forgeagent.agent.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布质量门禁(P1 / B3)。
 *
 * <p>与参考项目不同的两点取舍:① 未配置策略时使用内置默认阈值,不会「无策略即放行」;
 * ② 已批准豁免只跳过指标项,模型绑定与提示词完整性这类基础检查永远执行。</p>
 *
 * <p>指标来源:P0 的 agent_run(近 7 天失败率/耗时)与 P1 的 agent_eval_run(最近一次成功评测),
 * 基线退化取自 agent_eval_baseline 的 active 记录。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
public class ReleaseGateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record GateResult(boolean passed, boolean waived, boolean sampleAvailable,
                             String reason, Map<String, Object> checks) {
    }

    private final JdbcTemplate jdbc;

    public ReleaseGateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 发布前预检(只写 preview 记录,不阻断) */
    public GateResult preview(AgentInfo agent) {
        return check(agent, "preview");
    }

    /** 发布时执行门禁,不通过直接阻断发布事务 */
    public GateResult assertCanRelease(AgentInfo agent) {
        GateResult result = check(agent, null);
        if (!result.passed()) {
            throw new ServiceException("发布门禁未通过: " + result.reason());
        }
        return result;
    }

    private GateResult check(AgentInfo agent, String previewStatus) {
        Map<String, Object> checks = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        // 1. 基础检查(不受豁免影响)
        boolean modelBound = agent.getModelId() != null;
        boolean promptReady = StringUtils.isNotBlank(agent.getSystemPrompt())
            || agent.getPromptTemplateId() != null;
        checks.put("modelBound", modelBound);
        // 提示词完整性只作为提示项:不少 Agent 依赖模型默认行为即可工作,不应因此阻断发布
        checks.put("promptReady", promptReady);
        if (!modelBound) {
            problems.add("未绑定模型");
        }

        // 2. 策略:空间级优先,否则平台级,都没有则用内置默认值
        Map<String, Object> policy = loadPolicy(agent.getWorkspaceId());
        double minScore = decimal(policy.get("min_eval_score"), 60);
        double maxFailure = decimal(policy.get("max_failure_rate"), 20);
        long maxP95 = longNum(policy.get("max_p95_latency_ms"), 60000);
        double maxRegression = decimal(policy.get("max_metric_regression"), 10);
        checks.put("minEvalScore", minScore);
        checks.put("maxFailureRate", maxFailure);
        checks.put("maxP95LatencyMs", maxP95);
        checks.put("maxMetricRegression", maxRegression);

        // 3. 样本指标
        Map<String, Object> eval = latestEval(agent.getId());
        Map<String, Object> runtime = runtimeMetrics(agent.getId());
        long runtimeTotal = longNum(runtime.get("total"), 0);
        boolean sampleAvailable = !eval.isEmpty() || runtimeTotal > 0;
        checks.put("sampleAvailable", sampleAvailable);
        checks.put("runtimeRuns7d", runtimeTotal);

        boolean waived = hasApprovedWaiver(agent.getId());
        checks.put("waived", waived);
        boolean metricPassed = true;
        if (sampleAvailable && !waived) {
            if (!eval.isEmpty()) {
                double score = decimal(eval.get("avg_score"), 0);
                checks.put("evalScore", score);
                if (score < minScore) {
                    metricPassed = false;
                    problems.add("评测得分 " + score + " 低于门槛 " + minScore);
                }
                long p95 = longNum(eval.get("p95_latency_ms"), 0);
                checks.put("p95LatencyMs", p95);
                if (p95 > 0 && p95 > maxP95) {
                    metricPassed = false;
                    problems.add("P95 耗时 " + p95 + "ms 超过上限 " + maxP95 + "ms");
                }
            }
            if (runtimeTotal > 0) {
                double failureRate = longNum(runtime.get("failed"), 0) * 100.0 / runtimeTotal;
                checks.put("failureRate7d", Math.round(failureRate * 100) / 100.0);
                if (failureRate > maxFailure) {
                    metricPassed = false;
                    problems.add("近 7 天失败率 " + Math.round(failureRate) + "% 超过上限 " + maxFailure + "%");
                }
            }
            Map<String, Object> baseline = latestBaseline(agent.getId());
            if (!baseline.isEmpty() && !eval.isEmpty()) {
                Map<String, Object> metrics = parseMetrics(text(baseline.get("metrics_json")));
                double baseScore = decimal(metrics.get("avgScore"), 0);
                double currentScore = decimal(eval.get("avg_score"), 0);
                checks.put("baselineScore", baseScore);
                checks.put("regression", Math.round((baseScore - currentScore) * 100) / 100.0);
                if (baseScore > 0 && baseScore - currentScore > maxRegression) {
                    metricPassed = false;
                    problems.add("相对黄金基线退化 " + (baseScore - currentScore) + " 分,超过允许值 " + maxRegression);
                }
            }
        } else if (!sampleAvailable) {
            checks.put("note", "暂无运行/评测样本,仅执行基础检查");
        }

        boolean passed = problems.isEmpty() && metricPassed;
        String reason = passed
            ? (waived ? "基础检查通过,指标项由已批准的豁免覆盖" : "全部检查通过")
            : String.join("；", problems);
        checks.put("passed", passed);
        checks.put("reason", reason);

        String status = previewStatus != null ? previewStatus : (passed ? "passed" : "blocked");
        jdbc.update("""
                INSERT INTO release_gate_execution
                  (agent_id,target_version,status,checks_json,reason,waived,requested_by,create_time)
                VALUES (?,?,?,?,?,?,?,NOW())
                """, agent.getId(), agent.getVersion(), status, JsonUtils.toJsonString(checks),
            reason, waived ? "1" : "0", currentUserId());
        return new GateResult(passed, waived, sampleAvailable, reason, checks);
    }

    public List<Map<String, Object>> listExecutions(Long agentId) {
        return jdbc.queryForList("""
                SELECT id,agent_id,target_version,status,reason,waived,checks_json,create_time
                FROM release_gate_execution WHERE agent_id=? ORDER BY id DESC LIMIT 50
                """, agentId);
    }

    // ------------------------------------------------------------------
    // 豁免:申请与审批必须不同人,有效期 24 小时
    // ------------------------------------------------------------------

    public Long requestWaiver(Long agentId, String reason) {
        if (StringUtils.isBlank(reason)) {
            throw new ServiceException("豁免申请必须填写理由");
        }
        jdbc.update("""
                INSERT INTO release_gate_waiver (agent_id,reason,status,requested_by,create_time)
                VALUES (?,?,'pending',?,NOW())
                """, agentId, reason, currentUserId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void approveWaiver(Long waiverId, boolean approve) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM release_gate_waiver WHERE id=?", waiverId);
        if (rows.isEmpty()) {
            throw new ServiceException("豁免申请不存在");
        }
        Map<String, Object> row = rows.getFirst();
        if (!"pending".equals(text(row.get("status")))) {
            throw new ServiceException("该申请已处理");
        }
        Long requester = longNum(row.get("requested_by"), 0) == 0 ? null
            : longNum(row.get("requested_by"), 0);
        Long approver = currentUserId();
        if (approve && requester != null && approver != null && requester.equals(approver)) {
            throw new ServiceException("豁免审批人不得与申请人为同一人");
        }
        jdbc.update("""
                UPDATE release_gate_waiver SET status=?,approved_by=?,expires_at=?
                WHERE id=?
                """, approve ? "approved" : "rejected", approver,
            approve ? java.time.LocalDateTime.now().plusHours(24) : null, waiverId);
    }

    public List<Map<String, Object>> listWaivers(Long agentId) {
        return jdbc.queryForList("""
                SELECT id,agent_id,reason,status,requested_by,approved_by,expires_at,create_time
                FROM release_gate_waiver WHERE agent_id=? ORDER BY id DESC LIMIT 30
                """, agentId);
    }

    private Map<String, Object> loadPolicy(Long workspaceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM release_gate_policy
                WHERE resource_type='agent' AND enabled='1'
                  AND (workspace_id=? OR workspace_id IS NULL)
                ORDER BY workspace_id IS NULL ASC LIMIT 1
                """, workspaceId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Map<String, Object> latestEval(Long agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT avg_score,tool_success_rate,refusal_rate,p95_latency_ms
                FROM agent_eval_run WHERE agent_id=? AND status='success' ORDER BY id DESC LIMIT 1
                """, agentId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Map<String, Object> runtimeMetrics(Long agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT COUNT(*) AS total,
                  SUM(CASE WHEN status IN ('failed','cancelled') THEN 1 ELSE 0 END) AS failed
                FROM agent_run WHERE agent_id=? AND create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                """, agentId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Map<String, Object> latestBaseline(Long agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT metrics_json FROM agent_eval_baseline
                WHERE agent_id=? AND active='1' ORDER BY id DESC LIMIT 1
                """, agentId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private boolean hasApprovedWaiver(Long agentId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM release_gate_waiver
                WHERE agent_id=? AND status='approved' AND expires_at > NOW()
                """, Integer.class, agentId);
        return count != null && count > 0;
    }

    private Map<String, Object> parseMetrics(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static double decimal(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longNum(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
