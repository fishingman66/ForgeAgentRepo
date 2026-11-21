package com.forgeagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * F2:帮助中心自定义域名校验。
 *
 * <p>诚实边界:本仓库没有"平台规范域名"配置项,因此只能校验域名<b>格式合法</b>与
 * <b>DNS 可解析</b>,不能声称"CNAME 已正确指向平台"或"SSL 已签发"——那需要平台域名配置 +
 * ACME 证书编排(另立项)。状态值 RESOLVABLE 仅表示解析出至少一条 A/AAAA 记录。</p>
 *
 * <p>解析动作抽成 {@link #resolve(String)} 便于单测覆写,避免单测真连 DNS。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaasDomainVerifier {

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_RESOLVABLE = "RESOLVABLE";
    public static final String STATUS_UNRESOLVABLE = "UNRESOLVABLE";
    public static final String STATUS_INVALID = "INVALID";

    /** 与 AgentWorkspaceBo 的 @Pattern 同口径:纯主机名,不含协议/端口/路径,标签首尾不为 -。 */
    private static final Pattern HOST_PATTERN = Pattern.compile(
        "^(?=.{1,200}$)(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))+$");

    private final AgentWorkspaceMapper workspaceMapper;
    private final WorkspacePermissionHelper permissionHelper;

    /** 校验工作空间的域名并落状态;返回最终状态字符串。 */
    public String verify(Long workspaceId) {
        permissionHelper.checkWsAdmin(workspaceId);
        AgentWorkspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null) {
            throw new ServiceException("工作空间不存在");
        }
        String domain = StringUtils.trim(ws.getHelpCenterDomain());
        String status;
        if (StringUtils.isBlank(domain)) {
            status = STATUS_NONE;
        } else if (!HOST_PATTERN.matcher(domain).matches()) {
            status = STATUS_INVALID;
        } else {
            status = resolve(domain) ? STATUS_RESOLVABLE : STATUS_UNRESOLVABLE;
        }
        workspaceMapper.update(null, new LambdaUpdateWrapper<AgentWorkspace>()
            .eq(AgentWorkspace::getId, workspaceId)
            .set(AgentWorkspace::getDomainStatus, status)
            .set(AgentWorkspace::getDomainVerifiedAt, new Date()));
        log.info("[F2] 工作空间 {} 域名校验完成 domain={} status={}", workspaceId, domain, status);
        return status;
    }

    /** DNS 解析:解析出任意 A/AAAA 记录即视为可解析。可被测试覆写。 */
    protected boolean resolve(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addresses != null && addresses.length > 0;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** 域名被清空时重置状态,避免残留上一次的 RESOLVABLE。 */
    public void resetStatus(Long workspaceId) {
        workspaceMapper.update(null, new LambdaUpdateWrapper<AgentWorkspace>()
            .eq(AgentWorkspace::getId, workspaceId)
            .set(AgentWorkspace::getDomainStatus, STATUS_NONE)
            .set(AgentWorkspace::getDomainVerifiedAt, new Date()));
    }
}
