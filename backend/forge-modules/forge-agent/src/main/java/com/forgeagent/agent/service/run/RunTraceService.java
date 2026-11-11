package com.forgeagent.agent.service.run;

import com.forgeagent.common.json.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Agent 全链路运行轨迹服务(P0 / A2)。
 *
 * <p>设计要点:Trace 写入使用 {@code PROPAGATION_REQUIRES_NEW} 的显式短事务,
 * 与聊天主链路事务完全隔离——模型网络调用、工具执行期间不持有数据库连接,
 * 业务回滚也不会丢失已产生的诊断步骤。任何 Trace 写失败只记日志,绝不阻断对话。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
public class RunTraceService {

    /** 单个步骤 payload 落库上限,避免 SQL 明细/检索上下文把表撑爆 */
    private static final int MAX_PAYLOAD_CHARS = 8000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RunTraceService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 一次运行的起始元数据 */
    public record RunMeta(String runId, Long workspaceId, Long agentId, Long sessionId,
                          Long userId, String modelName, String runType) {
    }

    /** 创建运行主记录(重复调用不覆盖已有状态) */
    public void startRun(RunMeta meta) {
        if (meta == null || meta.runId() == null) {
            return;
        }
        write(() -> jdbc.update("""
                INSERT IGNORE INTO agent_run
                  (run_id,workspace_id,agent_id,session_id,user_id,model_name,run_type,status)
                VALUES (?,?,?,?,?,?,?,'running')
                """, meta.runId(), meta.workspaceId(), meta.agentId(), meta.sessionId(),
            meta.userId(), meta.modelName(), meta.runType()));
    }

    /** 记录一个阶段步骤 */
    public void step(String runId, String stepType, String stepName, Long latencyMs,
                     Integer tokenUsage, Object payload) {
        if (runId == null) {
            return;
        }
        write(() -> {
            Integer nextNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(step_no),0)+1 FROM agent_run_step WHERE run_id=?",
                Integer.class, runId);
            jdbc.update("""
                    INSERT INTO agent_run_step
                      (run_id,step_no,step_type,step_name,status,latency_ms,token_usage,payload)
                    VALUES (?,?,?,?,'success',?,?,?)
                    """, runId, nextNo == null ? 1 : nextNo, stepType, stepName,
                latencyMs, tokenUsage, json(payload));
        });
    }

    /** 首 Token 延迟:仅首次写入生效 */
    public void firstToken(String runId, long latencyMs) {
        if (runId == null) {
            return;
        }
        write(() -> jdbc.update("""
                UPDATE agent_run SET first_token_latency_ms=?
                WHERE run_id=? AND first_token_latency_ms IS NULL
                """, latencyMs, runId));
    }

    /** 结束运行 */
    public void finishRun(String runId, String status, long latencyMs,
                          Integer promptTokens, Integer completionTokens, Integer totalTokens,
                          String errorMessage) {
        if (runId == null) {
            return;
        }
        write(() -> jdbc.update("""
                UPDATE agent_run SET status=?,latency_ms=?,prompt_tokens=?,completion_tokens=?,
                  total_tokens=?,error_message=?,finish_time=NOW()
                WHERE run_id=?
                """, status, latencyMs, nz(promptTokens), nz(completionTokens), nz(totalTokens),
            truncate(errorMessage, 1000), runId));
    }

    /** 查询运行归属用户,用于 SSE 重放与 Trace 详情的越权校验 */
    public Long ownerOf(String runId) {
        try {
            List<Long> rows = jdbc.queryForList("SELECT user_id FROM agent_run WHERE run_id=?",
                Long.class, runId);
            return rows.isEmpty() ? null : rows.getFirst();
        } catch (Exception e) {
            log.warn("[RunTrace] 查询运行归属失败 runId={}: {}", runId, e.getMessage());
            return null;
        }
    }

    /** 运行详情:主记录 + 按序步骤列表 */
    public Map<String, Object> detail(String runId) {
        try {
            List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT * FROM agent_run WHERE run_id=?", runId);
            if (runs.isEmpty()) {
                return Map.of();
            }
            List<Map<String, Object>> steps = jdbc.queryForList("""
                    SELECT step_no,step_type,step_name,status,latency_ms,token_usage,payload,create_time
                    FROM agent_run_step WHERE run_id=? ORDER BY step_no,id
                    """, runId);
            return Map.of("run", runs.getFirst(), "steps", steps);
        } catch (Exception e) {
            log.warn("[RunTrace] 查询运行详情失败 runId={}: {}", runId, e.getMessage());
            return Map.of();
        }
    }

    /** 包装为独立短事务,失败仅记日志 */
    private void write(Runnable action) {
        try {
            tx.executeWithoutResult(status -> action.run());
        } catch (Exception e) {
            log.warn("[RunTrace] 轨迹写入失败(不影响对话): {}", e.getMessage());
        }
    }

    private static String json(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            return truncate(JsonUtils.toJsonString(payload), MAX_PAYLOAD_CHARS);
        } catch (Exception e) {
            return truncate(String.valueOf(payload), MAX_PAYLOAD_CHARS);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…[truncated]";
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
