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
}
