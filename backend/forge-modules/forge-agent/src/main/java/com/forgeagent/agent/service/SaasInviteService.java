package com.forgeagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.agent.domain.AgentWorkspaceInvite;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agent.mapper.AgentWorkspaceInviteMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * F2 SaaS 成员邀请:令牌明文只生成时回传一次,库里只存 SHA-256;
 * 接受邀请走「条件更新 used_count」实现单次/限次消费,并发安全。
 */
@Service
@RequiredArgsConstructor
public class SaasInviteService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentWorkspaceInviteMapper inviteMapper;
    private final com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper memberMapper;
    private final WorkspacePermissionHelper permissionHelper;

    /** 邀请结果:仅此处回传明文 token(形如 fgi_xxx)。 */
    public record InviteVo(String token, Date expiresAt, String role) {
    }

    private String sha256Hex(String raw) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 管理员创建邀请:role 仅允许 developer/runner(不能邀请 admin),默认 1 次、7 天有效。 */
    public InviteVo create(Long workspaceId, String role, Integer maxUses, Integer ttlHours) {
        permissionHelper.checkWsAdmin(workspaceId);
        String normalized = role == null ? "runner" : role;
        if (!WorkspaceRole.DEVELOPER.getCode().equals(normalized)
            && !WorkspaceRole.RUNNER.getCode().equals(normalized)) {
            throw new ServiceException("受邀角色只能是 developer 或 runner");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String plain = "fgi_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        int uses = maxUses == null || maxUses < 1 ? 1 : Math.min(maxUses, 100);
        int hours = ttlHours == null || ttlHours < 1 ? 168 : Math.min(ttlHours, 24 * 30);
        AgentWorkspaceInvite invite = new AgentWorkspaceInvite();
        invite.setWorkspaceId(workspaceId);
        invite.setTokenHash(sha256Hex(plain));
        invite.setRole(normalized);
        invite.setMaxUses(uses);
        invite.setUsedCount(0);
        invite.setExpiresAt(new Date(System.currentTimeMillis() + hours * 3600_000L));
        invite.setCreateBy(LoginHelper.getUserId());
        invite.setCreateTime(new Date());
        inviteMapper.insert(invite);
        return new InviteVo(plain, invite.getExpiresAt(), normalized);
    }

    /**
     * 接受邀请(需登录):条件更新 used_count 抢名额,成功后加入 workspace_member。
     * token 错误 / 过期 / 用尽一律拒绝,不泄漏空间信息。
     */
    public Long accept(String token, Long userId) {
        if (token == null || token.isBlank() || userId == null) {
            throw new ServiceException("邀请链接无效");
        }
        String hash = sha256Hex(token);
        AgentWorkspaceInvite invite = inviteMapper.selectOne(new LambdaQueryWrapper<AgentWorkspaceInvite>()
            .eq(AgentWorkspaceInvite::getTokenHash, hash));
        if (invite == null || invite.getExpiresAt().before(new Date())) {
            throw new ServiceException("邀请链接无效或已过期");
        }
        // 原子抢名额:used_count < max_uses 才 +1,多用户并发时只有一个成功
        int updated = inviteMapper.update(null, new LambdaUpdateWrapper<AgentWorkspaceInvite>()
            .eq(AgentWorkspaceInvite::getId, invite.getId())
            .lt(AgentWorkspaceInvite::getUsedCount, invite.getMaxUses())
            .setSql("used_count = used_count + 1"));
        if (updated != 1) {
            throw new ServiceException("邀请次数已用尽");
        }
        // 直接写成员关系:邀请令牌本身就是授权凭证,不能再走 addMember(它要求管理员)。
        // 重复加入由 uk_ws_user 唯一键兜底,视为幂等成功。
        com.forgeagent.agentcore.domain.AgentWorkspaceMember member = new com.forgeagent.agentcore.domain.AgentWorkspaceMember();
        member.setWorkspaceId(invite.getWorkspaceId());
        member.setUserId(userId);
        member.setRole(invite.getRole());
        member.setCreateBy(LoginHelper.getUserId());
        member.setCreateTime(new Date());
        try {
            memberMapper.insert(member);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 已是成员:名额已扣但关系不变,幂等成功
        }
        return invite.getWorkspaceId();
    }

    /** 管理员查看本空间邀请记录(只含哈希前缀,不含明文 token)。 */
    public java.util.List<AgentWorkspaceInvite> list(Long workspaceId) {
        permissionHelper.checkWsAdmin(workspaceId);
        return inviteMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceInvite>()
            .eq(AgentWorkspaceInvite::getWorkspaceId, workspaceId)
            .orderByDesc(AgentWorkspaceInvite::getCreateTime));
    }
}
