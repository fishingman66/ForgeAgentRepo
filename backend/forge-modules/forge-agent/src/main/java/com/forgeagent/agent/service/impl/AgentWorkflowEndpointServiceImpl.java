package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forgeagent.agent.domain.AgentApiKey;
import com.forgeagent.agent.domain.AgentWorkflowEndpoint;
import com.forgeagent.agent.domain.bo.AgentWorkflowEndpointBo;
import com.forgeagent.agent.domain.vo.AgentWorkflowEndpointVo;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agent.mapper.AgentApiKeyMapper;
import com.forgeagent.agent.mapper.AgentWorkflowEndpointMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agent.service.IAgentWorkflowEndpointService;
import com.forgeagent.agent.service.IAgentWorkflowService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工作流对外 API 端点 Service 实现(G2)
 *
 * <p>鉴权挂空间 API 密钥体系(明文 ak- 密钥 SHA-256 比对,不落明文);
 * 限流按端点每分钟 Redis 计数;执行复用触发器的幂等工作流链路(triggerRun)。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentWorkflowEndpointServiceImpl implements IAgentWorkflowEndpointService {

    private final AgentWorkflowEndpointMapper endpointMapper;
    private final AgentApiKeyMapper apiKeyMapper;
    private final AgentInfoMapper agentInfoMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final IAgentWorkflowService workflowService;
    private final WorkspacePermissionHelper permissionHelper;
    private final AuditLogHelper auditLogHelper;

    /** 超时中断执行用的虚拟线程池(LLM 阻塞调用安全) */
    private static final ExecutorService INVOKE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 体检 S1:单 IP 每分钟对 /workflow-api 的总调用上限(鉴权前生效) */
    private static final long IP_RATE_LIMIT_PER_MINUTE = 120;

    /** 体检 S1:单 IP 每分钟鉴权失败上限,超过后该分钟内直接拒绝 */
    private static final long AUTH_FAILURE_LIMIT_PER_MINUTE = 10;

    @Override
    public PageResult<AgentWorkflowEndpointVo> queryPageList(Long agentId, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentWorkflowEndpoint> wrapper = new LambdaQueryWrapper<AgentWorkflowEndpoint>()
            .eq(agentId != null, AgentWorkflowEndpoint::getAgentId, agentId)
            .orderByDesc(AgentWorkflowEndpoint::getId);
        List<AgentWorkflowEndpointVo> records;
        long total;
        if (agentId != null) {
            AgentInfo agent = agentInfoMapper.selectById(agentId);
            if (agent != null) {
                permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
            }
            Page<AgentWorkflowEndpointVo> page = endpointMapper.selectVoPage(pageQuery.build(), wrapper);
            records = page.getRecords();
            total = page.getTotal();
        } else {
            // 全量列表按空间范围裁剪(超管不限)
            List<Long> scope = resolveAdminScope();
            if (scope != null && scope.isEmpty()) {
                return PageResult.build(List.of(), 0);
            }
            if (scope != null) {
                List<Long> agentIds = agentInfoMapper.selectList(new LambdaQueryWrapper<AgentInfo>()
                        .select(AgentInfo::getId)
                        .in(AgentInfo::getWorkspaceId, scope))
                    .stream().map(AgentInfo::getId).toList();
                if (agentIds.isEmpty()) {
                    return PageResult.build(List.of(), 0);
                }
                wrapper.in(AgentWorkflowEndpoint::getAgentId, agentIds);
            }
            Page<AgentWorkflowEndpointVo> page = endpointMapper.selectVoPage(pageQuery.build(), wrapper);
            records = page.getRecords();
            total = page.getTotal();
        }
        fillDisplayFields(records);
        return PageResult.build(records, total);
    }

    /** 可见范围:超管不限(null);其他用户返回其担任空间管理员的空间ID集合(与审批中心同口径) */
    private List<Long> resolveAdminScope() {
        if (LoginHelper.isSuperAdmin()) {
            return null;
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return List.of();
        }
        return memberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, userId)
                .eq(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode()))
            .stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
    }

    private void fillDisplayFields(List<AgentWorkflowEndpointVo> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> agentIds = records.stream().map(AgentWorkflowEndpointVo::getAgentId)
            .filter(Objects::nonNull).distinct().toList();
        if (!agentIds.isEmpty()) {
            Map<Long, String> names = agentInfoMapper.selectByIds(agentIds).stream()
                .collect(java.util.stream.Collectors.toMap(AgentInfo::getId, AgentInfo::getName));
            records.forEach(v -> {
                v.setAgentName(names.getOrDefault(v.getAgentId(), "已删除"));
                v.setInvokePath("/workflow-api/" + v.getEndpointCode());
            });
        }
        List<Long> keyIds = records.stream().map(AgentWorkflowEndpointVo::getApiKeyId)
            .filter(Objects::nonNull).distinct().toList();
        if (!keyIds.isEmpty()) {
            Map<Long, String> prefixes = apiKeyMapper.selectByIds(keyIds).stream()
                .collect(java.util.stream.Collectors.toMap(AgentApiKey::getId, AgentApiKey::getKeyPrefix));
            records.forEach(v -> {
                String prefix = v.getApiKeyId() == null ? null : prefixes.get(v.getApiKeyId());
                v.setMaskedKey(prefix == null ? "未绑定" : prefix + "****");
            });
        } else {
            records.forEach(v -> v.setMaskedKey("同空间任意密钥"));
        }
    }

    @Override
    public Long insertByBo(AgentWorkflowEndpointBo bo) {
        AgentInfo agent = agentInfoMapper.selectById(bo.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        permissionHelper.checkWsAdmin(agent.getWorkspaceId());
        if (StringUtils.isBlank(agent.getWorkflowJson())) {
            throw new ServiceException("目标 Agent 尚未编排工作流,请先在画布保存工作流");
        }
        validateAndFill(bo);
        // 端点编码全局唯一
        Long dup = endpointMapper.selectCount(new LambdaQueryWrapper<AgentWorkflowEndpoint>()
            .eq(AgentWorkflowEndpoint::getEndpointCode, bo.getEndpointCode()));
        if (dup != null && dup > 0) {
            throw new ServiceException("端点编码已存在:" + bo.getEndpointCode());
        }
        validateApiKeyBinding(agent.getWorkspaceId(), bo.getApiKeyId());
        AgentWorkflowEndpoint entity = MapstructUtils.convert(bo, AgentWorkflowEndpoint.class);
        entity.setId(null);
        entity.setWorkspaceId(agent.getWorkspaceId());
        entity.setStatus(StringUtils.defaultIfBlank(entity.getStatus(), "0"));
        endpointMapper.insert(entity);
        auditLogHelper.record(agent.getWorkspaceId(), "workflow-api.create", "workflow_endpoint",
            String.valueOf(entity.getId()),
            "创建工作流 API 端点[" + bo.getEndpointCode() + "]绑定 Agent " + agent.getName(),
            null, java.util.Map.of("endpointCode", bo.getEndpointCode(), "agentId", agent.getId()),
            "0", "WARN");
        return entity.getId();
    }

    @Override
    public Boolean updateByBo(AgentWorkflowEndpointBo bo) {
        AgentWorkflowEndpoint exist = endpointMapper.selectById(bo.getId());
        if (exist == null) {
            throw new ServiceException("端点不存在");
        }
        permissionHelper.checkWsAdmin(exist.getWorkspaceId());
        // 编码与归属不可改,仅允许改名称/密钥/限流/超时/状态
        bo.setEndpointCode(null);
        bo.setAgentId(null);
        validateAndFill(bo);
        if (bo.getApiKeyId() != null) {
            validateApiKeyBinding(exist.getWorkspaceId(), bo.getApiKeyId());
        }
        AgentWorkflowEndpoint update = MapstructUtils.convert(bo, AgentWorkflowEndpoint.class);
        boolean ok = endpointMapper.updateById(update) > 0;
        if (ok) {
            auditLogHelper.record(exist.getWorkspaceId(), "workflow-api.update", "workflow_endpoint",
                String.valueOf(exist.getId()), "更新工作流 API 端点[" + exist.getEndpointCode() + "]配置",
                null, java.util.Map.of("endpointCode", exist.getEndpointCode()), "0", "WARN");
        }
        return ok;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的启用状态");
        }
        AgentWorkflowEndpoint exist = endpointMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException("端点不存在");
        }
        permissionHelper.checkWsAdmin(exist.getWorkspaceId());
        AgentWorkflowEndpoint update = new AgentWorkflowEndpoint();
        update.setId(id);
        update.setStatus(status);
        return endpointMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentWorkflowEndpoint exist = endpointMapper.selectById(id);
            if (exist == null) {
                continue;
            }
            permissionHelper.checkWsAdmin(exist.getWorkspaceId());
            endpointMapper.deleteById(id);
            auditLogHelper.record(exist.getWorkspaceId(), "workflow-api.delete", "workflow_endpoint",
                String.valueOf(id), "删除工作流 API 端点[" + exist.getEndpointCode() + "]",
                null, java.util.Map.of("endpointCode", exist.getEndpointCode()), "0", "RISK");
        }
        return true;
    }

    @Override
    public Map<String, Object> invoke(String endpointCode, String authorizationHeader, String body,
                                      String idempotencyHeader, String clientIp) {
        long start = System.currentTimeMillis();
        // 1. IP 级预限流(体检 S1):鉴权之前先卡流量,失败尝试也计数,防密钥无限爆破
        enforceIpRateLimit(clientIp);
        // 2. 定位端点(对外消息统一模糊化,避免构成端点存在性/归属探测 oracle)
        AgentWorkflowEndpoint endpoint = endpointMapper.selectOne(new LambdaQueryWrapper<AgentWorkflowEndpoint>()
            .eq(AgentWorkflowEndpoint::getEndpointCode, endpointCode)
            .last("limit 1"));
        if (endpoint == null || !"0".equals(endpoint.getStatus())) {
            throw new ServiceException("工作流端点不存在或无权访问");
        }
        // 3. 鉴权:Authorization: Bearer ak-...,SHA-256 比对(不落明文)
        AgentApiKey apiKey = requireApiKey(endpoint, authorizationHeader, clientIp);
        // 4. 端点级限流:按端点每分钟计数(鉴权成功后)
        enforceRateLimit(endpoint);
        // 5. 目标 Agent 校验
        AgentInfo agent = agentInfoMapper.selectById(endpoint.getAgentId());
        if (agent == null) {
            throw new ServiceException("端点绑定的 Agent 不存在");
        }
        if (StringUtils.isBlank(agent.getWorkflowJson())) {
            throw new ServiceException("端点绑定的 Agent 未配置工作流");
        }
        // 6. 幂等键(可选) + 幂等执行,超时硬顶
        String inputs = StringUtils.isBlank(body) ? "{}" : body;
        String idemKey = StringUtils.isBlank(idempotencyHeader)
            ? null : "api:" + endpoint.getId() + ":" + idempotencyHeader.trim();
        Long operatorUserId = endpoint.getCreateBy() == null ? 1L : endpoint.getCreateBy();
        int timeoutSeconds = Math.max(1, Math.min(300,
            endpoint.getTimeoutSeconds() == null ? 60 : endpoint.getTimeoutSeconds()));
        WorkflowTrialRunVo vo;
        Future<WorkflowTrialRunVo> future = INVOKE_EXECUTOR.submit(
            () -> workflowService.triggerRun(endpoint.getAgentId(), inputs, operatorUserId, idemKey));
        try {
            vo = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            // 体检 S1:超时必须取消后台任务,否则 run 继续烧模型配额
            future.cancel(true);
            throw new ServiceException("工作流执行超时(" + timeoutSeconds + "s),请携带 Idempotency-Key 重试或到运行记录查看结果");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ServiceException(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ServiceException("工作流调用被中断");
        }
        long cost = System.currentTimeMillis() - start;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", vo.getRunId());
        result.put("status", vo.getStatus());
        result.put("output", extractEndOutput(vo));
        result.put("message", vo.getMessage());
        result.put("costMs", cost);
        if (apiKey != null) {
            auditLogHelper.record(endpoint.getWorkspaceId(), "workflow-api.invoke", "workflow_endpoint",
                String.valueOf(endpoint.getId()),
                "外部调用端点[" + endpointCode + "],状态=" + vo.getStatus(),
                null, java.util.Map.of("runId", String.valueOf(vo.getRunId()),
                    "apiKeyId", apiKey.getId(), "costMs", cost),
                "success".equals(vo.getStatus()) ? "0" : "1", "INFO");
        }
        return result;
    }

    /**
     * IP 级预限流(体检 S1):每 IP 每分钟 120 次;鉴权失败单独计数(10 次/分钟)——
     * 两者都在鉴权之前执行,保证爆破尝试本身被限速。
     */
    private void enforceIpRateLimit(String clientIp) {
        String ip = StringUtils.isBlank(clientIp) ? "unknown" : clientIp;
        String minute = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .format(LocalDateTime.now());
        // 总量预限流:防任意来源刷接口
        org.redisson.api.RAtomicLong total = RedisUtils.getClient()
            .getAtomicLong("agent:wfe:ip:" + ip + ":" + minute);
        long n = total.incrementAndGet();
        if (n == 1) {
            total.expire(Duration.ofSeconds(70));
        }
        if (n > IP_RATE_LIMIT_PER_MINUTE) {
            throw new ServiceException("调用过于频繁,请稍后再试");
        }
    }

    /** 鉴权失败按 IP 计数,超阈值直接拒绝(不再触发查询) */
    private void countAuthFailure(String clientIp) {
        String ip = StringUtils.isBlank(clientIp) ? "unknown" : clientIp;
        String minute = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .format(LocalDateTime.now());
        try {
            org.redisson.api.RAtomicLong fail = RedisUtils.getClient()
                .getAtomicLong("agent:wfe:authfail:" + ip + ":" + minute);
            long n = fail.incrementAndGet();
            if (n == 1) {
                fail.expire(Duration.ofSeconds(70));
            }
        } catch (Exception ignore) {
            // 计数失败不阻断主流程
        }
    }

    /**
     * 鉴权:端点绑定密钥时须完全一致;未绑定时接受同空间任意有效密钥(免登录端点一律要求密钥)。
     * 体检 S1/M3:鉴权失败记 IP 计数并抛统一模糊消息,不区分「密钥无效/跨空间/未绑定」
     */
    private AgentApiKey requireApiKey(AgentWorkflowEndpoint endpoint, String authorizationHeader, String clientIp) {
        // 体检 S1:失败计数超限先拒绝,不再触发密钥查询(防爆破打 DB)
        String currentMinute = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .format(LocalDateTime.now());
        Long recentFails = failCountOf(clientIp, currentMinute);
        if (recentFails != null && recentFails > AUTH_FAILURE_LIMIT_PER_MINUTE) {
            throw new ServiceException("失败次数过多,请稍后再试");
        }
        String plainKey = null;
        if (StringUtils.isNotBlank(authorizationHeader)) {
            String value = authorizationHeader.trim();
            plainKey = value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value;
        }
        AgentApiKey apiKey = null;
        if (StringUtils.isNotBlank(plainKey)) {
            apiKey = apiKeyMapper.selectOne(new LambdaQueryWrapper<AgentApiKey>()
                .eq(AgentApiKey::getKeyHash, sha256Hex(plainKey))
                .last("limit 1"));
        }
        boolean ok = apiKey != null && "0".equals(apiKey.getStatus())
            && (endpoint.getWorkspaceId() == null
                || Objects.equals(apiKey.getWorkspaceId(), endpoint.getWorkspaceId()))
            && (endpoint.getApiKeyId() == null || Objects.equals(apiKey.getId(), endpoint.getApiKeyId()));
        if (!ok) {
            countAuthFailure(clientIp);
            throw new ServiceException("无权访问该端点,请检查 API 密钥");
        }
        return apiKey;
    }

    private Long failCountOf(String clientIp, String minute) {
        try {
            return RedisUtils.getClient()
                .getAtomicLong("agent:wfe:authfail:" + (StringUtils.isBlank(clientIp) ? "unknown" : clientIp) + ":" + minute)
                .get();
        } catch (Exception e) {
            return null;
        }
    }

    /** 限流:按端点每分钟 Redis 计数,超出 rate_limit 拒绝 */
    private void enforceRateLimit(AgentWorkflowEndpoint endpoint) {
        int limit = endpoint.getRateLimit() == null ? 60 : endpoint.getRateLimit();
        String minute = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .format(LocalDateTime.now());
        org.redisson.api.RAtomicLong counter = RedisUtils.getClient()
            .getAtomicLong("agent:wfe:rate:" + endpoint.getId() + ":" + minute);
        long n = counter.incrementAndGet();
        if (n == 1) {
            counter.expire(Duration.ofSeconds(70));
        }
        if (n > limit) {
            throw new ServiceException("调用过于频繁,超出每分钟 " + limit + " 次上限");
        }
    }

    private void validateAndFill(AgentWorkflowEndpointBo bo) {
        if (bo.getRateLimit() != null) {
            bo.setRateLimit(Math.max(1, Math.min(6000, bo.getRateLimit())));
        }
        if (bo.getTimeoutSeconds() != null) {
            bo.setTimeoutSeconds(Math.max(1, Math.min(300, bo.getTimeoutSeconds())));
        }
    }

    /** 密钥绑定校验:必须存在、启用、同空间 */
    private void validateApiKeyBinding(Long workspaceId, Long apiKeyId) {
        if (apiKeyId == null) {
            return;
        }
        AgentApiKey apiKey = apiKeyMapper.selectById(apiKeyId);
        if (apiKey == null) {
            throw new ServiceException("绑定的 API 密钥不存在");
        }
        if (!Objects.equals(apiKey.getWorkspaceId(), workspaceId)) {
            throw new ServiceException("绑定的 API 密钥与 Agent 不属于同一工作空间");
        }
        if ("1".equals(apiKey.getStatus())) {
            throw new ServiceException("绑定的 API 密钥已吊销");
        }
    }

    /** 对外响应体上限(体检 E2):超长输出截断并给标记,防止单次调用撑爆响应与调用方内存 */
    private static final int OUTPUT_MAX_CHARS = 64 * 1024;

    /** 提取工作流回复:优先 end 节点输出,其次轨迹末条/状态消息 */
    private String extractEndOutput(WorkflowTrialRunVo vo) {
        if (vo == null) {
            return null;
        }
        String raw;
        if ("pending_approval".equals(vo.getStatus())) {
            raw = vo.getMessage();
        } else {
            raw = vo.getMessage();
            if (vo.getTrace() != null) {
                for (int i = vo.getTrace().size() - 1; i >= 0; i--) {
                    WorkflowTrialRunVo.NodeTrace t = vo.getTrace().get(i);
                    if ("end".equals(t.getNodeType()) && t.getOutput() != null && !t.getOutput().isBlank()) {
                        raw = t.getOutput();
                        break;
                    }
                }
            }
        }
        if (raw == null || raw.length() <= OUTPUT_MAX_CHARS) {
            return raw;
        }
        return raw.substring(0, OUTPUT_MAX_CHARS)
            + "\n...(输出超过 " + OUTPUT_MAX_CHARS + " 字符已截断,完整内容见运行记录)";
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new ServiceException("当前环境不支持 SHA-256");
        }
    }
}
