package com.forgeagent.agent.controller;

import com.forgeagent.agent.domain.AgentWorkspaceInvite;
import com.forgeagent.agent.service.SaasInviteService;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * F2 SaaS 外壳管理端点:成员邀请 + 品牌外观。
 * 受 saas.enabled 开关保护(关闭时这些端点仍可达,但前端不展示入口;Space 归属校验照常)。
 */
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@RequestMapping("/agent/saas")
public class SaasController {

    private final SaasInviteService inviteService;
    /** F2:帮助中心域名校验(格式 + DNS 可解析,不声称 CNAME/SSL 生效)。 */
    private final com.forgeagent.agent.service.SaasDomainVerifier domainVerifier;

    /** 管理员创建邀请链接(明文 token 仅此一次返回)。 */
    @PostMapping("/invites")
    public R<SaasInviteService.InviteVo> createInvite(@RequestParam Long workspaceId,
        @RequestParam(required = false) String role,
        @RequestParam(required = false) Integer maxUses,
        @RequestParam(required = false) Integer ttlHours) {
        return R.data(inviteService.create(workspaceId, role, maxUses, ttlHours));
    }

    /** 登录用户接受邀请(token 来自邀请链接)。 */
    @PostMapping("/invites/accept")
    public R<Long> acceptInvite(@RequestParam String token) {
        return R.data(inviteService.accept(token, LoginHelper.getUserId()));
    }

    /** 管理员查看邀请记录(不含明文 token)。 */
    @GetMapping("/invites")
    public R<List<AgentWorkspaceInvite>> listInvites(@RequestParam Long workspaceId) {
        return R.data(inviteService.list(workspaceId));
    }

    /**
     * F2:校验并落库当前帮助中心域名状态。
     * 只判断格式与 DNS 可解析性;不校验 CNAME 记录类型,也不签发 SSL。
     */
    @PostMapping("/domain/verify")
    public R<String> verifyDomain(@RequestParam Long workspaceId) {
        return R.data(domainVerifier.verify(workspaceId));
    }
}
