package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agent.domain.AgentTrigger;
import com.forgeagent.agent.domain.AgentTriggerLog;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentTriggerBo;
import com.forgeagent.agent.domain.vo.AgentTriggerLogVo;
import com.forgeagent.agent.domain.vo.AgentTriggerVo;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.domain.vo.TriggerRunVo;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agent.mapper.AgentTriggerLogMapper;
import com.forgeagent.agent.mapper.AgentTriggerMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.agent.service.IAgentTriggerService;
import com.forgeagent.agent.service.IAgentWorkflowService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.agent.event.TriggerChangedEvent;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Agent 触发器 Service 实现
 *
 * <p>Cron 定时触发与 Webhook 事件触发统一走 {@link #execute} 执行入口,
 * 目标是 Agent 时以消息文本发起一轮对话,目标是工作流时执行该 Agent 的当前工作流。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTriggerServiceImpl implements IAgentTriggerService {

    private final AgentTriggerMapper triggerMapper;
    private final AgentTriggerLogMapper logMapper;
    private final AgentInfoMapper agentInfoMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final IAgentChatService chatService;
    private final IAgentWorkflowService workflowService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.forgeagent.agentcore.service.AuditLogHelper auditLogHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> CONTENT_KEYS = List.of("content", "prompt", "message", "query", "text");

    @Override
    public PageResult<AgentTriggerVo> queryPageList(AgentTriggerBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentTrigger> wrapper = new LambdaQueryWrapper<AgentTrigger>()
            .like(StringUtils.isNotBlank(bo.getKeyword()), AgentTrigger::getName, bo.getKeyword())
            .eq(StringUtils.isNotBlank(bo.getTriggerTypeQuery()), AgentTrigger::getTriggerType,
                bo.getTriggerTypeQuery())
            .eq(AgentTrigger::getDelFlag, 0L)
            .orderByDesc(AgentTrigger::getId);
        List<Long> scope = resolveWorkspaceScope();
        if (scope != null) {
            if (scope.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(AgentTrigger::getWorkspaceId, scope);
        }
        Page<AgentTriggerVo> page = triggerMapper.selectVoPage(pageQuery.build(), wrapper);
        fillTargetName(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    private void fillTargetName(List<AgentTriggerVo> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> agentIds = records.stream().map(AgentTriggerVo::getTargetId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (agentIds.isEmpty()) {
            return;
        }
        java.util.Map<Long, String> nameMap = agentInfoMapper.selectByIds(agentIds).stream()
            .collect(java.util.stream.Collectors.toMap(AgentInfo::getId, AgentInfo::getName));
        records.forEach(v -> v.setTargetName(nameMap.getOrDefault(v.getTargetId(), "已删除")));
    }

    /**
     * 工作空间可见范围:超管不限制,普通用户仅可见自己加入的空间
     */
    private List<Long> resolveWorkspaceScope() {
        if (LoginHelper.isSuperAdmin()) {
            return null;
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return List.of();
        }
        return memberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, userId)).stream()
            .map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
    }

    @Override
    public AgentTriggerVo queryById(Long id) {
        AgentTriggerVo vo = triggerMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("触发器不存在");
        }
        permissionHelper.checkWsDeveloper(vo.getWorkspaceId());
        fillTargetName(new ArrayList<>(List.of(vo)));
        return vo;
    }

    @Override
    public Boolean insertByBo(AgentTriggerBo bo) {
        AgentInfo agent = requireTargetAgent(bo.getTargetId());
        permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        validateRule(bo.getTriggerType(), bo.getCronExpr());
        AgentTrigger entity = MapstructUtils.convert(bo, AgentTrigger.class);
        entity.setId(null);
        entity.setWorkspaceId(agent.getWorkspaceId());
        entity.setEnabled(StringUtils.isBlank(bo.getEnabled()) ? "0" : bo.getEnabled());
        entity.setRunCount(0);
        if ("webhook".equals(bo.getTriggerType())) {
            entity.setCronExpr(null);
            // 密钥由服务端生成,避免用户设置弱密钥
            entity.setSecret(newSecret());
        } else {
            entity.setSecret(null);
        }
        boolean ok = triggerMapper.insert(entity) > 0;
        publishChanged(ok);
        if (ok) {
            auditLogHelper.record(agent.getWorkspaceId(), "trigger.create", "trigger", String.valueOf(entity.getId()),
                "创建触发器[" + entity.getName() + "]类型=" + entity.getTriggerType(),
                null,
                java.util.Map.of("name", entity.getName(), "triggerType", entity.getTriggerType(),
                    "targetType", entity.getTargetType(), "targetId", entity.getTargetId(),
                    "enabled", entity.getEnabled()),
                "0", "WARN");
        }
        return ok;
    }

    @Override
    public Boolean updateByBo(AgentTriggerBo bo) {
        AgentTrigger exist = triggerMapper.selectById(bo.getId());
        if (exist == null) {
            throw new ServiceException("触发器不存在");
        }
        permissionHelper.checkWsDeveloper(exist.getWorkspaceId());
        validateRule(bo.getTriggerType(), bo.getCronExpr());
        AgentTrigger update = MapstructUtils.convert(bo, AgentTrigger.class);
        update.setWorkspaceId(null);
        update.setSecret(null);
        update.setRunCount(null);
        update.setLastRunTime(null);
        update.setLastRunStatus(null);
        if ("webhook".equals(bo.getTriggerType())) {
            update.setCronExpr(null);
        }
        boolean ok = triggerMapper.updateById(update) > 0;
        publishChanged(ok);
        if (ok) {
            auditLogHelper.record(exist.getWorkspaceId(), "trigger.update", "trigger", String.valueOf(bo.getId()),
                "更新触发器[" + exist.getName() + "]配置",
                java.util.Map.of("name", exist.getName(), "triggerType", exist.getTriggerType(),
                    "enabled", exist.getEnabled(), "cronExpr", StringUtils.defaultString(exist.getCronExpr(), "")),
                java.util.Map.of("name", bo.getName(), "triggerType", bo.getTriggerType(),
                    "enabled", bo.getEnabled(), "cronExpr", StringUtils.defaultString(bo.getCronExpr(), "")),
                "0", "WARN");
        }
        return ok;
    }

    @Override
    public Boolean changeEnabled(Long id, String enabled) {
        if (!"0".equals(enabled) && !"1".equals(enabled)) {
            throw new ServiceException("非法的启用状态");
        }
        AgentTrigger exist = triggerMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException("触发器不存在");
        }
        permissionHelper.checkWsDeveloper(exist.getWorkspaceId());
        if ("0".equals(enabled) && "cron".equals(exist.getTriggerType())) {
            validateCron(exist.getCronExpr());
        }
        AgentTrigger update = new AgentTrigger();
        update.setId(id);
        update.setEnabled(enabled);
        boolean ok = triggerMapper.updateById(update) > 0;
        publishChanged(ok);
        if (ok) {
            String action = "0".equals(enabled) ? "启用" : "停用";
            auditLogHelper.record(exist.getWorkspaceId(), "trigger.toggle", "trigger", String.valueOf(id),
                action + "触发器[" + exist.getName() + "]",
                java.util.Map.of("enabled", exist.getEnabled()),
                java.util.Map.of("enabled", enabled),
                "0", "INFO");
        }
        return ok;
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentTrigger exist = triggerMapper.selectById(id);
            if (exist == null) {
                continue;
            }
            permissionHelper.checkWsDeveloper(exist.getWorkspaceId());
            logMapper.delete(new LambdaQueryWrapper<AgentTriggerLog>().eq(AgentTriggerLog::getTriggerId, id));
            triggerMapper.deleteById(id);
            auditLogHelper.record(exist.getWorkspaceId(), "trigger.delete", "trigger", String.valueOf(id),
                "删除触发器[" + exist.getName() + "]",
                java.util.Map.of("name", exist.getName(), "triggerType", exist.getTriggerType(),
                    "targetType", exist.getTargetType(), "targetId", exist.getTargetId()),
                null, "0", "RISK");
        }
        publishChanged(true);
        return true;
    }

    /**
     * 通知调度器刷新 Cron 注册
     */
    private void publishChanged(boolean changed) {
        if (changed) {
            eventPublisher.publishEvent(new TriggerChangedEvent(this));
        }
    }

    @Override
    public PageResult<AgentTriggerLogVo> queryLogPageList(Long triggerId, PageQuery pageQuery) {
        if (triggerId == null) {
            throw new ServiceException("触发器ID不能为空");
        }
        AgentTrigger exist = triggerMapper.selectById(triggerId);
        if (exist == null) {
            throw new ServiceException("触发器不存在");
        }
        permissionHelper.checkWsDeveloper(exist.getWorkspaceId());
        LambdaQueryWrapper<AgentTriggerLog> wrapper = new LambdaQueryWrapper<AgentTriggerLog>()
            .eq(AgentTriggerLog::getTriggerId, triggerId)
            .eq(AgentTriggerLog::getDelFlag, 0L)
            .orderByDesc(AgentTriggerLog::getId);
        Page<AgentTriggerLogVo> page = logMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public TriggerRunVo runNow(Long id) {
        AgentTrigger trigger = requireTrigger(id);
        permissionHelper.checkWsDeveloper(trigger.getWorkspaceId());
        TriggerRunVo vo = execute(trigger, "manual", null, null);
        auditLogHelper.record(trigger.getWorkspaceId(), "trigger.manual.fire", "trigger", String.valueOf(id),
            "手动触发[" + trigger.getName() + "],结果=" + vo.getStatus(),
            null,
            java.util.Map.of("runStatus", vo.getStatus(), "costMs", vo.getCostMs()),
            "success".equals(vo.getStatus()) ? "0" : "1", "INFO");
        return vo;
    }

    @Override
    public TriggerRunVo runWebhook(String secret, String body, String idempotencyHeader) {
        if (StringUtils.isBlank(secret)) {
            throw new ServiceException("Webhook 密钥不能为空");
        }
        AgentTrigger trigger = triggerMapper.selectOne(new LambdaQueryWrapper<AgentTrigger>()
            .eq(AgentTrigger::getSecret, secret).last("limit 1"));
        if (trigger == null) {
            throw new ServiceException("Webhook 密钥无效");
        }
        if (!"webhook".equals(trigger.getTriggerType())) {
            throw new ServiceException("该密钥不属于 Webhook 触发器");
        }
        if (!"0".equals(trigger.getEnabled())) {
            throw new ServiceException("触发器已停用");
        }
        // G3:请求头 Idempotency-Key 存在时按其防重,缺失不做幂等(不误伤合法的重复调用)
        String key = StringUtils.isBlank(idempotencyHeader)
            ? null : "webhook:" + trigger.getId() + ":" + idempotencyHeader.trim();
        return execute(trigger, "webhook", body, key);
    }

    @Override
    public TriggerRunVo executeCron(Long id) {
        AgentTrigger trigger = triggerMapper.selectById(id);
        if (trigger == null || !"0".equals(trigger.getEnabled())) {
            return null;
        }
        // G3:Cron 幂等键=triggerId+调度分钟,防多实例重复投递与同周期重试烧钱
        // (秒级 Cron 极不推荐;若确需秒级调度,同分钟内的多次触发会被去重)
        String key = "cron:" + id + ":"
            + new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new Date());
        return execute(trigger, "cron", null, key);
    }

    @Override
    public List<AgentTriggerVo> listEnabledCronTriggers() {
        return triggerMapper.selectVoList(new LambdaQueryWrapper<AgentTrigger>()
            .eq(AgentTrigger::getTriggerType, "cron")
            .eq(AgentTrigger::getEnabled, "0")
            .eq(AgentTrigger::getDelFlag, 0L));
    }

    /**
     * 统一执行入口:分发到 Agent 对话或工作流执行,并落执行记录与最近状态。
     * G3:idempotencyKey 非空时,工作流目标走幂等执行(cron/webhook);对话目标暂不去重(会话幂等语义不同)
     */
    private TriggerRunVo execute(AgentTrigger trigger, String invokeSource, String overrideBody,
                                 String idempotencyKey) {
        long start = System.currentTimeMillis();
        TriggerRunVo vo = new TriggerRunVo();
        vo.setTriggerId(trigger.getId());
        vo.setTargetType(trigger.getTargetType());
        vo.setTargetId(trigger.getTargetId());
        AgentInfo agent = agentInfoMapper.selectById(trigger.getTargetId());
        vo.setTargetName(agent == null ? "已删除" : agent.getName());
        // 触发器归属人作为运行身份;归属人为空时回退超管
        Long operatorUserId = trigger.getCreateBy() == null ? 1L : trigger.getCreateBy();
        String status;
        String message;
        try {
            if (agent == null) {
                throw new ServiceException("目标 Agent 不存在");
            }
            if ("workflow".equals(trigger.getTargetType())) {
                String inputs = resolveWorkflowInputs(trigger, overrideBody);
                WorkflowTrialRunVo wvo = workflowService.triggerRun(trigger.getTargetId(), inputs,
                    operatorUserId, idempotencyKey);
                vo.setRunId(wvo.getRunId());
                vo.setRunStatus(wvo.getStatus());
                vo.setOutput("执行完成,可到运行记录查看节点轨迹");
                if ("pending_approval".equals(wvo.getStatus())) {
                    status = "success";
                    message = "已触发人工审批,工作流暂停待审批";
                } else {
                    status = "success".equals(wvo.getStatus()) ? "success" : "fail";
                    message = wvo.getMessage();
                }
            } else {
                String content = resolveAgentContent(trigger, overrideBody);
                ChatSendVo cvo = chatService.sendAsUser(trigger.getTargetId(), content, operatorUserId);
                vo.setSessionId(cvo.getSessionId());
                String reply = cvo.getAssistantMessage() == null ? null
                    : cvo.getAssistantMessage().getContent();
                vo.setOutput(truncate(reply, 200));
                status = "success";
                message = "对话执行完成";
            }
        } catch (Exception e) {
            status = "fail";
            message = "执行失败:" + safeMessage(e);
            log.warn("[触发器] {} 执行失败: {}", trigger.getName(), safeMessage(e));
        }
        long cost = System.currentTimeMillis() - start;
        vo.setStatus(status);
        vo.setMessage(message);
        vo.setCostMs(cost);
        // 更新触发器运行状态(runCount 走 SQL 原子自增,避免并发触发读-改-写丢计数)
        AgentTrigger update = new AgentTrigger();
        update.setId(trigger.getId());
        update.setLastRunTime(new Date());
        update.setLastRunStatus(status);
        triggerMapper.updateById(update);
        triggerMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentTrigger>()
            .eq(AgentTrigger::getId, trigger.getId())
            .setSql("run_count = coalesce(run_count, 0) + 1"));
        // 落执行记录
        AgentTriggerLog logEntity = new AgentTriggerLog();
        logEntity.setTriggerId(trigger.getId());
        logEntity.setTriggerName(trigger.getName());
        logEntity.setTriggerType(trigger.getTriggerType());
        logEntity.setTargetType(trigger.getTargetType());
        logEntity.setTargetId(trigger.getTargetId());
        logEntity.setInvokeSource(invokeSource);
        logEntity.setStatus(status);
        logEntity.setMessage(truncate(message, 500));
        logEntity.setCostMs(cost);
        logEntity.setRunTime(new Date());
        try {
            logMapper.insert(logEntity);
        } catch (Exception e) {
            log.warn("[触发器] 写执行记录失败: {}", e.getMessage());
        }
        return vo;
    }

    /**
     * Agent 目标:解析消息文本;外部负载若为 JSON 且含 content/prompt 等字段则取其值
     */
    private String resolveAgentContent(AgentTrigger trigger, String overrideBody) {
        String raw = StringUtils.isNotBlank(overrideBody) ? overrideBody : trigger.getInputs();
        if (StringUtils.isBlank(raw)) {
            return "（触发器触发,无输入内容）";
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isObject()) {
                for (String key : CONTENT_KEYS) {
                    if (node.hasNonNull(key)) {
                        return node.path(key).asText("");
                    }
                }
            }
        } catch (Exception ignore) {
            // 非 JSON,按纯文本处理
        }
        return raw;
    }

    /**
     * 工作流目标:将外部负载字段合并进触发器的输入模板 JSON
     */
    private String resolveWorkflowInputs(AgentTrigger trigger, String overrideBody) {
        String base = StringUtils.isBlank(trigger.getInputs()) ? "{}" : trigger.getInputs();
        if (StringUtils.isBlank(overrideBody)) {
            return base;
        }
        try {
            JsonNode override = objectMapper.readTree(overrideBody);
            if (!override.isObject()) {
                return overrideBody;
            }
            JsonNode baseNode = objectMapper.readTree(base);
            ObjectNode merged = baseNode.isObject() ? (ObjectNode) baseNode : objectMapper.createObjectNode();
            override.fieldNames().forEachRemaining(k -> merged.set(k, override.get(k)));
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            return overrideBody;
        }
    }

    private AgentTrigger requireTrigger(Long id) {
        AgentTrigger trigger = triggerMapper.selectById(id);
        if (trigger == null) {
            throw new ServiceException("触发器不存在");
        }
        return trigger;
    }

    private AgentInfo requireTargetAgent(Long targetId) {
        AgentInfo agent = targetId == null ? null : agentInfoMapper.selectById(targetId);
        if (agent == null) {
            throw new ServiceException("目标 Agent 不存在");
        }
        return agent;
    }

    private void validateRule(String triggerType, String cronExpr) {
        if ("cron".equals(triggerType)) {
            validateCron(cronExpr);
        } else if (!"webhook".equals(triggerType)) {
            throw new ServiceException("不支持的触发类型:" + triggerType);
        }
    }

    private void validateCron(String cronExpr) {
        if (StringUtils.isBlank(cronExpr)) {
            throw new ServiceException("定时触发器必须配置 Cron 表达式");
        }
        try {
            new CronTrigger(cronExpr);
        } catch (Exception e) {
            throw new ServiceException("Cron 表达式非法:" + e.getMessage());
        }
    }

    private String newSecret() {
        return "wh_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return StringUtils.isBlank(msg) ? e.getClass().getSimpleName() : msg;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
