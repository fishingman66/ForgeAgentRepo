package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.AgentApiKey;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentApiKeyBo;
import com.forgeagent.agent.domain.vo.AgentApiKeyCreateVo;
import com.forgeagent.agent.domain.vo.AgentApiKeyVo;
import com.forgeagent.agent.mapper.AgentApiKeyMapper;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agent.service.IAgentApiKeyService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 空间 API Key Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentApiKeyServiceImpl implements IAgentApiKeyService {

    private final AgentApiKeyMapper apiKeyMapper;
    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final AuditLogHelper auditLogHelper;

    @Override
    public PageResult<AgentApiKeyVo> queryPageList(AgentApiKeyBo bo, PageQuery pageQuery) {
        List<Long> scope = resolveScope(bo.getWorkspaceId());
        if (scope != null && scope.isEmpty()) {
            return PageResult.build(Collections.emptyList(), 0);
        }
        LambdaQueryWrapper<AgentApiKey> wrapper = new LambdaQueryWrapper<AgentApiKey>()
            .in(scope != null, AgentApiKey::getWorkspaceId, scope)
            .eq(bo.getWorkspaceId() != null, AgentApiKey::getWorkspaceId, bo.getWorkspaceId())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentApiKey::getStatus, bo.getStatus())
            .orderByDesc(AgentApiKey::getId);
        Page<AgentApiKeyVo> page = apiKeyMapper.selectVoPage(pageQuery.build(), wrapper);
        fillDisplayFields(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentApiKeyCreateVo createByBo(AgentApiKeyBo bo) {
        permissionHelper.checkWsAdmin(bo.getWorkspaceId());
        AgentWorkspace ws = workspaceMapper.selectById(bo.getWorkspaceId());
        if (ws == null) {
            throw new ServiceException("工作空间不存在");
        }
        // 明文 ak-+UUID 仅此一次返回,库内仅保存 SHA-256 哈希与脱敏前缀
        String plainKey = "ak-" + UUID.randomUUID().toString().replace("-", "");
        AgentApiKey entity = new AgentApiKey();
        entity.setWorkspaceId(bo.getWorkspaceId());
        entity.setName(bo.getName());
        entity.setKeyPrefix(plainKey.substring(0, 8));
        entity.setKeyHash(sha256Hex(plainKey));
        entity.setStatus("0");
        apiKeyMapper.insert(entity);
        auditLogHelper.record(bo.getWorkspaceId(), "apikey.create", "api_key", String.valueOf(entity.getId()),
            "创建 API Key[" + bo.getName() + "]");
        AgentApiKeyCreateVo vo = new AgentApiKeyCreateVo();
        vo.setId(entity.getId());
        vo.setPlainKey(plainKey);
        return vo;
    }

    @Override
    public Boolean revoke(Long id) {
        AgentApiKey exists = apiKeyMapper.selectById(id);
        if (exists == null) {
            throw new ServiceException("API Key 不存在");
        }
        permissionHelper.checkWsAdmin(exists.getWorkspaceId());
        if ("1".equals(exists.getStatus())) {
            throw new ServiceException("该 Key 已吊销");
        }
        AgentApiKey update = new AgentApiKey();
        update.setId(id);
        update.setStatus("1");
        boolean ok = apiKeyMapper.updateById(update) > 0;
        if (ok) {
            auditLogHelper.record(exists.getWorkspaceId(), "apikey.revoke", "api_key", String.valueOf(id),
                "吊销 API Key[" + exists.getName() + "]");
        }
        return ok;
    }

    /**
     * 可见范围:超管不限;其他用户仅可见所属空间
     */
    private List<Long> resolveScope(Long workspaceId) {
        if (workspaceId != null) {
            permissionHelper.checkWsMember(workspaceId);
            return null;
        }
        if (LoginHelper.isSuperAdmin()) {
            return null;
        }
        return memberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId()))
            .stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
    }

    private void fillDisplayFields(List<AgentApiKeyVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<Long, String> wsNames = workspaceMapper.selectByIds(
                list.stream().map(AgentApiKeyVo::getWorkspaceId).filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(AgentWorkspace::getId, AgentWorkspace::getName, (a, b) -> a));
        // keyPrefix 不属于 VO 字段,批量回查实体补齐脱敏展示
        Map<Long, String> prefixes = apiKeyMapper.selectByIds(
                list.stream().map(AgentApiKeyVo::getId).filter(Objects::nonNull).toList())
            .stream().collect(Collectors.toMap(AgentApiKey::getId, AgentApiKey::getKeyPrefix, (a, b) -> a));
        list.forEach(vo -> {
            vo.setWorkspaceName(wsNames.get(vo.getWorkspaceId()));
            String prefix = prefixes.get(vo.getId());
            vo.setMaskedKey(prefix == null ? "ak-****" : prefix + "****");
        });
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("当前环境不支持 SHA-256");
        }
    }
}
