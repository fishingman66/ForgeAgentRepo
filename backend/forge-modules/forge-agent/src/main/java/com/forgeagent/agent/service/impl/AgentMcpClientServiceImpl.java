package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forgeagent.agent.domain.AgentMcpClient;
import com.forgeagent.agent.domain.bo.AgentMcpClientBo;
import com.forgeagent.agent.domain.vo.AgentMcpClientTokenVo;
import com.forgeagent.agent.domain.vo.AgentMcpClientVo;
import com.forgeagent.agent.mapper.AgentMcpClientMapper;
import com.forgeagent.agent.service.IAgentMcpClientService;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对外 MCP 客户端 Service 实现(08方案 / A5 增强)。
 *
 * <p>令牌安全口径:明文只在创建/轮转时返回一次,库里只留 SHA-256 与 12 位前缀;
 * 比对走哈希等值查询(256 位随机串,时序攻击无收益),不做明文常量时间比较。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentMcpClientServiceImpl implements IAgentMcpClientService {

    /** 令牌前缀:便于在日志/配置里一眼看出是平台的 MCP 令牌 */
    public static final String TOKEN_PREFIX = "fgm_";

    /** 允许的授权域 */
    private static final Set<String> ALLOWED_SCOPES = Set.of("knowledge", "skill");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentMcpClientMapper clientMapper;
    private final ResourceAccessGuard resourceGuard;

    @Override
    public PageResult<AgentMcpClientVo> queryPageList(AgentMcpClientBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentMcpClient> wrapper = new LambdaQueryWrapper<AgentMcpClient>()
            .like(StringUtils.isNotBlank(bo.getName()), AgentMcpClient::getName, bo.getName())
            .orderByDesc(AgentMcpClient::getId);
        resourceGuard.applyVisible(wrapper, AgentMcpClient::getWorkspaceId);
        Page<AgentMcpClientVo> page = clientMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentMcpClientVo queryById(Long id) {
        AgentMcpClient entity = requireClient(id);
        resourceGuard.assertReadable(entity.getWorkspaceId());
        return MapstructUtils.convert(entity, AgentMcpClientVo.class);
    }

    @Override
    public AgentMcpClientTokenVo createClient(AgentMcpClientBo bo) {
        // 平台全量(workspaceId 为空)等价于「共享资源」,仅超管可建
        Long scope = resourceGuard.resolveCreateWorkspace(bo.getWorkspaceId(), bo.getWorkspaceId() == null);
        String token = generateToken();
        AgentMcpClient entity = new AgentMcpClient();
        entity.setName(bo.getName());
        entity.setTokenHash(sha256Hex(token));
        entity.setTokenPrefix(prefixOf(token));
        entity.setWorkspaceId(scope);
        entity.setScopes(normalizeScopes(bo.getScopes()));
        entity.setEnabled("0");
        entity.setExpireTime(parseTime(bo.getExpireTime()));
        entity.setRemark(bo.getRemark());
        clientMapper.insert(entity);
        log.info("[McpClient] 新建客户端 id={} name={} workspace={} scopes={}",
            entity.getId(), entity.getName(), scope, entity.getScopes());
        return tokenVo(entity, token);
    }

    @Override
    public Boolean updateByBo(AgentMcpClientBo bo) {
        AgentMcpClient exists = requireClient(bo.getId());
        resourceGuard.assertWritable(exists.getWorkspaceId());
        Long scope = bo.getWorkspaceId() == null ? null
            : resourceGuard.resolveCreateWorkspace(bo.getWorkspaceId(), false);
        // 显式 set:updateById 会忽略 null,过期时间/备注清空必须走 wrapper
        return clientMapper.update(null, new LambdaUpdateWrapper<AgentMcpClient>()
            .eq(AgentMcpClient::getId, bo.getId())
            .set(AgentMcpClient::getName, bo.getName())
            .set(AgentMcpClient::getWorkspaceId, scope)
            .set(AgentMcpClient::getScopes, normalizeScopes(bo.getScopes()))
            .set(AgentMcpClient::getExpireTime, parseTime(bo.getExpireTime()))
            .set(AgentMcpClient::getRemark, bo.getRemark())) > 0;
    }

    @Override
    public Boolean changeStatus(Long id, String enabled) {
        if (!"0".equals(enabled) && !"1".equals(enabled)) {
            throw new ServiceException("非法的客户端状态");
        }
        AgentMcpClient exists = requireClient(id);
        resourceGuard.assertWritable(exists.getWorkspaceId());
        return clientMapper.update(null, new LambdaUpdateWrapper<AgentMcpClient>()
            .eq(AgentMcpClient::getId, id)
            .set(AgentMcpClient::getEnabled, enabled)) > 0;
    }

    @Override
    public AgentMcpClientTokenVo rotateToken(Long id) {
        AgentMcpClient exists = requireClient(id);
        resourceGuard.assertWritable(exists.getWorkspaceId());
        String token = generateToken();
        clientMapper.update(null, new LambdaUpdateWrapper<AgentMcpClient>()
            .eq(AgentMcpClient::getId, id)
            .set(AgentMcpClient::getTokenHash, sha256Hex(token))
            .set(AgentMcpClient::getTokenPrefix, prefixOf(token))
            .set(AgentMcpClient::getLastUsedTime, null));
        exists.setTokenPrefix(prefixOf(token));
        log.info("[McpClient] 令牌已轮转 id={} name={}", id, exists.getName());
        return tokenVo(exists, token);
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentMcpClient exists = requireClient(id);
            resourceGuard.assertWritable(exists.getWorkspaceId());
        }
        return clientMapper.deleteByIds(ids) > 0;
    }

    private AgentMcpClient requireClient(Long id) {
        AgentMcpClient entity = id == null ? null : clientMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("MCP 客户端不存在");
        }
        return entity;
    }

    private static AgentMcpClientTokenVo tokenVo(AgentMcpClient entity, String token) {
        AgentMcpClientTokenVo vo = new AgentMcpClientTokenVo();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setToken(token);
        vo.setTokenPrefix(entity.getTokenPrefix());
        return vo;
    }

    /** 32 字节随机 → base64url 无填充(43 字符) + 前缀 */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 令牌哈希:库里只存这个值 */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("令牌哈希计算失败", e);
        }
    }

    /** 展示前缀:fgm_ + 8 位,足够人工区分,不足以还原 */
    static String prefixOf(String token) {
        return token.length() <= TOKEN_PREFIX.length() + 8 ? token : token.substring(0, TOKEN_PREFIX.length() + 8);
    }

    /** 授权域规范化:去空、去重、排序、白名单校验 */
    static String normalizeScopes(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String scope = part.trim().toLowerCase();
            if (scope.isEmpty()) {
                continue;
            }
            if (!ALLOWED_SCOPES.contains(scope)) {
                throw new ServiceException("不支持的授权域: " + scope + "(可选 knowledge,skill)");
            }
            scopes.add(scope);
        }
        return String.join(",", new ArrayList<>(scopes));
    }

    /** 过期时间解析:支持 "yyyy-MM-dd HH:mm:ss" / ISO / 纯日期 */
    static LocalDateTime parseTime(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String text = raw.trim();
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text).atStartOfDay();
            }
            if (text.contains("T")) {
                return LocalDateTime.parse(text.length() > 19 ? text.substring(0, 19) : text, DATE_TIME_ISO);
            }
            return LocalDateTime.parse(text, DATE_TIME);
        } catch (Exception e) {
            throw new ServiceException("时间格式不正确,应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
