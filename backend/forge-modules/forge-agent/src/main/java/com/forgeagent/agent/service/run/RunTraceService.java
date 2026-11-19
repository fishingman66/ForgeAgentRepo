package com.forgeagent.agent.service.run;

import com.forgeagent.agent.domain.AgentRun;
import com.forgeagent.agent.domain.AgentRunStep;
import com.forgeagent.agent.mapper.AgentRunMapper;
import com.forgeagent.agent.mapper.AgentRunStepMapper;
import com.forgeagent.common.json.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Agent 全链路运行轨迹服务(P0 / A2)。
 *
 * <p>设计要点:Trace 写入使用 {@code PROPAGATION_REQUIRES_NEW} 的显式短事务,
 * 与聊天主链路事务完全隔离——模型网络调用、工具执行期间不持有数据库连接,
 * 业务回滚也不会丢失已产生的诊断步骤。任何 Trace 写失败只记日志,绝不阻断对话。</p>
 *
 * <p>JdbcTemplate 治理(第 3 批):读写全部走 MP Mapper;INSERT IGNORE 与
 * 「首 Token 仅首写」保留 SQL 原生语义,分别由 ASSIGN_ID 主键幂等与
 * {@code IS NULL} 条件更新等价承载。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
public class RunTraceService {

    /** 单个步骤 payload 落库上限,避免 SQL 明细/检索上下文把表撑爆 */
    private static final int MAX_PAYLOAD_CHARS = 8000;

    private final AgentRunMapper runMapper;
    private final AgentRunStepMapper stepMapper;
    private final TransactionTemplate tx;

    public RunTraceService(AgentRunMapper runMapper, AgentRunStepMapper stepMapper,
                           PlatformTransactionManager transactionManager) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 一次运行的起始元数据 */
    public record RunMeta(String runId, Long workspaceId, Long agentId, Long sessionId,
                          Long userId, String modelName, String runType) {
    }

    /** 创建运行主记录(重复调用不覆盖已有状态;主键冲突即忽略) */
    public void startRun(RunMeta meta) {
        if (meta == null || meta.runId() == null) {
            return;
        }
        write(() -> {
            AgentRun entity = new AgentRun();
            entity.setRunId(meta.runId());
            entity.setWorkspaceId(meta.workspaceId());
            entity.setAgentId(meta.agentId());
            entity.setSessionId(meta.sessionId());
            entity.setUserId(meta.userId());
            entity.setModelName(meta.modelName());
            entity.setRunType(meta.runType());
            entity.setStatus("running");
            try {
                runMapper.insert(entity);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 等价原 INSERT IGNORE:重复 startRun 不覆盖已有状态
                log.debug("[RunTrace] 运行已存在,跳过重复创建 runId={}", meta.runId());
            }
        });
    }

    /** 记录一个阶段步骤 */
    public void step(String runId, String stepType, String stepName, Long latencyMs,
                     Integer tokenUsage, Object payload) {
        if (runId == null) {
            return;
        }
        write(() -> {
            Integer nextNo = stepMapper.nextStepNo(runId);
            AgentRunStep entity = new AgentRunStep();
            entity.setRunId(runId);
            entity.setStepNo(nextNo == null ? 1 : nextNo);
            entity.setStepType(stepType);
            entity.setStepName(stepName);
            entity.setStatus("success");
            entity.setLatencyMs(latencyMs);
            entity.setTokenUsage(tokenUsage);
            entity.setPayload(json(payload));
            stepMapper.insert(entity);
        });
    }

    /** 首 Token 延迟:仅首次写入生效 */
    public void firstToken(String runId, long latencyMs) {
        if (runId == null) {
            return;
        }
        write(() -> runMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getRunId, runId)
                .isNull(AgentRun::getFirstTokenLatencyMs)
                .set(AgentRun::getFirstTokenLatencyMs, latencyMs)));
    }

    /** 结束运行 */
    public void finishRun(String runId, String status, long latencyMs,
                          Integer promptTokens, Integer completionTokens, Integer totalTokens,
                          String errorMessage) {
        if (runId == null) {
            return;
        }
        write(() -> {
            AgentRun update = new AgentRun();
            update.setStatus(status);
            update.setLatencyMs(latencyMs);
            update.setPromptTokens(promptTokens);
            update.setCompletionTokens(completionTokens);
            update.setTotalTokens(totalTokens);
            update.setErrorMessage(truncate(errorMessage, 1000));
            update.setFinishTime(new java.util.Date());
            runMapper.update(update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentRun>()
                    .eq(AgentRun::getRunId, runId));
        });
    }

    /** 查询运行归属用户,用于 SSE 重放与 Trace 详情的越权校验 */
    public Long ownerOf(String runId) {
        try {
            return runMapper.selectOwner(runId);
        } catch (Exception e) {
            log.warn("[RunTrace] 查询运行归属失败 runId={}: {}", runId, e.getMessage());
            return null;
        }
    }

    /** 运行详情:主记录 + 按序步骤列表 */
    public com.forgeagent.agent.domain.vo.RunTraceDetailVo detail(String runId) {
        try {
            AgentRun run = runMapper.selectById(runId);
            if (run == null) {
                return new com.forgeagent.agent.domain.vo.RunTraceDetailVo();
            }
            com.forgeagent.agent.domain.vo.RunTraceDetailVo detail =
                new com.forgeagent.agent.domain.vo.RunTraceDetailVo();
            detail.setRun(new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(run, com.forgeagent.agent.domain.vo.AgentRunVo.class));
            detail.setSteps(stepMapper.listSteps(runId));
            return detail;
        } catch (Exception e) {
            log.warn("[RunTrace] 查询运行详情失败 runId={}: {}", runId, e.getMessage());
            return new com.forgeagent.agent.domain.vo.RunTraceDetailVo();
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
}
