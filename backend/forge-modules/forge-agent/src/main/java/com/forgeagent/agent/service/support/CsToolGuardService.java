package com.forgeagent.agent.service.support;

import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.callcenter.common.CsPiiMasker;
import com.forgeagent.common.redis.utils.RedisUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.redisson.api.RAtomicLong;
import com.forgeagent.agent.domain.AgentCsActionLog;
import com.forgeagent.agent.domain.AgentCsAuditLog;
import com.forgeagent.agent.mapper.AgentCsActionLogMapper;
import com.forgeagent.agent.mapper.AgentCsAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reactor.core.publisher.Mono;

/**
 * C1/C2/C3/C5:客服工具护栏(批次 C)——挂在 ApprovalGateTool 之后的最后一道闸门。
 *
 * <p>只在客服链路生效(csGuardContext 非空),控制台路径零改动。四项职责:</p>
 * <ul>
 * <li>C1 风险分级:READ 默认放行;WRITE/HIGH_RISK 强护栏。</li>
 * <li>C2 归属下推(ADR-02):平台只强制传递访客上下文;渠道未配置 identity_binding
 *     时 WRITE/HIGH_RISK 一律 deny(fail-closed,与 D8 同向),归属判定由业务系统在
 *     其接口内完成,平台保证凭证不可绕过并全量留痕。</li>
 * <li>C3 幂等与额度:action_key 模板按入参展开,(conversation_id, action_key) 唯一占位,
 *     重复命中直接回放上次结果摘要;daily_limit 走 Redis 原子计数日窗口,超限拦截。
 *     Redis 不可用降级放行(与渠道限流同口径,不阻断主链路)。</li>
 * <li>C5 调用审计:WRITE/HIGH_RISK 全量留痕 + 失败的 READ 留痕(state 为 ERROR/DENIED/INTERRUPTED
 *     或执行抛异常;防日志量失控),结果码与耗时进 detail 及结构化列,参数过 {@link CsPiiMasker} 脱敏。</li>
 * </ul>
 *
 * <p>写入的两张表(agent_cs_action_log / agent_cs_audit_log)为客服域所有,
 * 与 {@code CsGovernanceFacadeImpl} 直接读 agent_info 同一口径:护栏必须落在
 * 工具装饰层(forge-agent),跨模块契约反而把执行链拆散。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsToolGuardService {

    private static final String QUOTA_PREFIX = "forge:cs:action:";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final int SUMMARY_MAX = 300;

    private final AgentCsActionLogMapper actionLogMapper;
    private final AgentCsAuditLogMapper auditLogMapper;

    /** 构造工具装饰器:仅对平台注册工具(名字能对上实体)生效,MCP/内置工具原样透传。 */
    public UnaryOperator<AgentTool> decorator(CsChatFacade.GuardContext ctx,
                                              Map<String, com.forgeagent.agent.domain.AgentTool> toolsByName) {
        if (ctx == null) {
            return UnaryOperator.identity();
        }
        return tool -> {
            com.forgeagent.agent.domain.AgentTool def = toolsByName.get(tool.getName());
            return def == null ? tool : new CsToolGuard(tool, def, ctx, actionLogMapper, auditLogMapper);
        };
    }

    /**
     * 幂等键展开:模板 {param} 占位符从工具入参取值;任一占位参数缺失时
     * 退化为工具级键(粒度粗但安全,宁可多拦不漏拦)。
     */
    static String resolveActionKey(String template, Map<String, Object> input, String toolName) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String resolved = template;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            Object value = input == null ? null : input.get(matcher.group(1));
            if (value == null || String.valueOf(value).isBlank()) {
                return toolName;
            }
            resolved = resolved.replace("{" + matcher.group(1) + "}", String.valueOf(value));
        }
        return toolName + ":" + resolved;
    }

    /**
     * 客服护栏装饰器:包装在审批门之外的最后一道。
     */
    record CsToolGuard(AgentTool delegate, com.forgeagent.agent.domain.AgentTool def,
                       CsChatFacade.GuardContext ctx, AgentCsActionLogMapper actionLogMapper,
                       AgentCsAuditLogMapper auditLogMapper) implements AgentTool {

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public Map<String, Object> getParameters() {
            return delegate.getParameters();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String risk = def.getRiskLevel() == null ? "READ" : def.getRiskLevel();
            boolean write = "WRITE".equals(risk) || "HIGH_RISK".equals(risk);

            // C2:未配置身份绑定的渠道,写操作 fail-closed(归属校验下推业务系统,平台兜底线)
            if (write && !ctx.identityBindingEnabled()) {
                audit("GUARD_DENY", risk, param, "identity_binding 未配置,写操作被拦截", "DENIED", null);
                return Mono.just(ToolResultBlock.error(
                    "该操作需要先完成访客身份核验，当前渠道未开启身份绑定，已转人工处理。"));
            }

            Reservation reservation = reserveActionLog(risk, param);
            if (reservation.duplicate()) {
                // 幂等命中:直接回放上次结果,不再执行
                return Mono.just(ToolResultBlock.error(duplicateMessage()));
            }
            Long logId = reservation.logId();

            if (def.getDailyLimit() != null && def.getDailyLimit() > 0
                && !withinDailyLimit(def.getName(), def.getDailyLimit())) {
                audit("QUOTA_BLOCK", risk, param, "动作级日限次 " + def.getDailyLimit() + " 已用尽", "QUOTA_BLOCKED", null);
                markLog(logId, "BLOCKED", "日限次已用尽");
                return Mono.just(ToolResultBlock.error(
                    "该操作今日执行次数已达上限，已为你登记，请稍后再试或转人工处理。"));
            }

            final long startMs = System.currentTimeMillis();
            return delegate.callAsync(param)
                .doOnNext(block -> {
                    String state = block.getState() == null ? "UNKNOWN" : block.getState().name();
                    boolean failed = "ERROR".equals(state) || "DENIED".equals(state)
                        || "INTERRUPTED".equals(state);
                    // 非失败一律完成动作日志(内核默认 RUNNING 亦按成功处理,与既有 finish 口径一致)
                    finish(logId, !failed, block);
                    // C5:WRITE/HIGH_RISK 全量留痕;READ 只在失败态留痕(防日志量失控)
                    if (write || failed) {
                        audit("TOOL_CALL", risk, param, null, state,
                            (int) (System.currentTimeMillis() - startMs));
                    }
                })
                .doOnError(e -> {
                    finish(logId, false, null);
                    audit("TOOL_CALL", risk, param, e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "ERROR", (int) (System.currentTimeMillis() - startMs));
                })
                .onErrorResume(e -> Mono.just(ToolResultBlock.error(
                    "工具 [" + getName() + "] 执行失败: " + e.getMessage())));
        }

        private String duplicateMessage() {
            return "该操作此前已执行过，本次重复请求已拦截（如需重新办理请说明原因转人工）。";
        }

        /** 幂等占位结果:duplicate=true 表示重复命中(不再执行);logId 供收尾更新。 */
        private record Reservation(Long logId, boolean duplicate) {
            static final Reservation NONE = new Reservation(null, false);
        }

        /**
         * 幂等占位:已存在记录且非 FAILED 时判重复;FAILED 允许重试(占位行复用,更新回 RUNNING)。
         * 占位写库异常按降级放行(不阻断主链路),只是失去收尾更新。
         */
        private Reservation reserveActionLog(String risk, ToolCallParam param) {
            String template = def.getActionKey();
            if (template == null || template.isBlank()) {
                return Reservation.NONE;
            }
            String actionKey = resolveActionKey(template, param.getInput(), def.getName());
            AgentCsActionLog existing;
            try {
                existing = actionLogMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentCsActionLog>()
                        .eq(AgentCsActionLog::getConversationId, ctx.conversationId())
                        .eq(AgentCsActionLog::getActionKey, actionKey)
                        .last("LIMIT 1"));
            } catch (Exception e) {
                log.warn("[CsToolGuard] 幂等查询失败(降级放行) conversation={} key={}: {}",
                    ctx.conversationId(), actionKey, e.getMessage());
                return Reservation.NONE;
            }
            if (existing != null) {
                String status = existing.getStatus() == null ? "" : existing.getStatus();
                if ("FAILED".equals(status)) {
                    actionLogMapper.update(null, new LambdaUpdateWrapper<AgentCsActionLog>()
                        .eq(AgentCsActionLog::getId, existing.getId())
                        .set(AgentCsActionLog::getStatus, "RUNNING")
                        .set(AgentCsActionLog::getCreateTime, new java.util.Date()));
                    return new Reservation(existing.getId(), false);
                }
                log.info("[CsToolGuard] 幂等命中 conversation={} tool={} key={} prevStatus={}",
                    ctx.conversationId(), def.getName(), actionKey, status);
                return new Reservation(null, true);
            }
            try {
                AgentCsActionLog insert = new AgentCsActionLog();
                insert.setConversationId(ctx.conversationId());
                insert.setVisitorId(ctx.visitorId());
                insert.setWorkspaceId(ctx.workspaceId());
                insert.setChannelCode(ctx.channelCode());
                insert.setToolName(def.getName());
                insert.setActionKey(actionKey);
                insert.setRiskLevel(risk);
                insert.setStatus("RUNNING");
                insert.setCreateTime(new java.util.Date());
                actionLogMapper.insert(insert);
                return new Reservation(insert.getId(), false);
            } catch (Exception e) {
                log.warn("[CsToolGuard] 幂等占位失败(降级放行) conversation={} key={}: {}",
                    ctx.conversationId(), actionKey, e.getMessage());
                return Reservation.NONE;
            }
        }

        /** C3:动作级日限次,Redis 原子计数日窗口;Redis 不可用降级放行(与渠道限流同口径)。 */
        private boolean withinDailyLimit(String toolName, int limit) {
            try {
                RAtomicLong counter = RedisUtils.getClient().getAtomicLong(QUOTA_PREFIX
                    + ctx.workspaceId() + ":" + ctx.visitorId() + ":" + toolName);
                long value = counter.incrementAndGet();
                if (value == 1) {
                    counter.expire(Duration.ofDays(1));
                }
                return value <= limit;
            } catch (Exception | LinkageError e) {
                // RedisUtils 静态初始化失败抛 ExceptionInInitializerError(Error 族),必须一并降级
                log.warn("[CsToolGuard] Redis 计数不可用,降级放行 tool={}: {}", toolName, e.getMessage());
                return true;
            }
        }

        private void finish(Long logId, boolean success, ToolResultBlock block) {
            if (logId == null) {
                return;
            }
            try {
                String summary = extractSummary(block);
                if (summary != null && summary.length() > SUMMARY_MAX) {
                    summary = summary.substring(0, SUMMARY_MAX);
                }
                actionLogMapper.update(null, new LambdaUpdateWrapper<AgentCsActionLog>()
                    .eq(AgentCsActionLog::getId, logId)
                    .set(AgentCsActionLog::getStatus, success ? "DONE" : "FAILED")
                    .set(AgentCsActionLog::getResultSummary, summary));
            } catch (Exception e) {
                log.warn("[CsToolGuard] 动作日志收尾失败 logId={}: {}", logId, e.getMessage());
            }
        }

        private void markLog(Long logId, String status, String summary) {
            if (logId == null) {
                return;
            }
            try {
                actionLogMapper.update(null, new LambdaUpdateWrapper<AgentCsActionLog>()
                    .eq(AgentCsActionLog::getId, logId)
                    .set(AgentCsActionLog::getStatus, status)
                    .set(AgentCsActionLog::getResultSummary, summary));
            } catch (Exception e) {
                log.warn("[CsToolGuard] 动作日志标记失败 logId={}: {}", logId, e.getMessage());
            }
        }

        /** 工具结果摘要:遍历 output 取 TextBlock 文本(与 WorkingMemoryGuard 同口径)。 */
        static String extractSummary(ToolResultBlock block) {
            if (block == null || block.getOutput() == null) {
                return null;
            }
            StringBuilder full = new StringBuilder();
            for (io.agentscope.core.message.ContentBlock part : block.getOutput()) {
                if (part instanceof io.agentscope.core.message.TextBlock text) {
                    if (full.length() > 0) {
                        full.append('\n');
                    }
                    full.append(text.getText());
                }
            }
            return full.isEmpty() ? null : full.toString();
        }

        /** C5:调用审计,参数过 PII 脱敏;结果码/耗时同时进 detail 与结构化列;静默失败不阻断工具链。 */
        private void audit(String eventType, String risk, ToolCallParam param, String extra,
                           String resultCode, Integer durationMs) {
            try {
                String params;
                try {
                    params = MAPPER.writeValueAsString(param.getInput() == null ? Map.of() : param.getInput());
                } catch (Exception e) {
                    params = "{}";
                }
                String detail = "tool=" + def.getName() + " risk=" + risk
                    + " result=" + resultCode
                    + (durationMs == null ? "" : " ms=" + durationMs)
                    + " params=" + CsPiiMasker.mask(params)
                    + (extra == null ? "" : " extra=" + extra);
                if (detail.length() > 900) {
                    detail = detail.substring(0, 900);
                }
                AgentCsAuditLog log = new AgentCsAuditLog();
                log.setChannelCode(ctx.channelCode());
                log.setEventType(eventType);
                log.setActorType("VISITOR");
                log.setActorId(String.valueOf(ctx.visitorId()));
                log.setDetail(detail);
                log.setWorkspaceId(ctx.workspaceId());
                log.setResultCode(resultCode);
                log.setDurationMs(durationMs);
                log.setCreateTime(new java.util.Date());
                auditLogMapper.insert(log);
            } catch (Exception e) {
                log.warn("[CsToolGuard] 审计写入失败 tool={}: {}", def.getName(), e.getMessage());
            }
        }
    }
}
