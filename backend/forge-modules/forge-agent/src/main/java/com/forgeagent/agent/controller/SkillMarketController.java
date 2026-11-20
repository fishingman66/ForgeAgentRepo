package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.forgeagent.agent.domain.vo.AgentSkillVo;
import com.forgeagent.agent.service.SkillMarketService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 / C5:技能市场(只读发现 + 一键绑定)。
 *
 * <p>只要求登录,不要求 {@code agent:skill:*} 管理权限:
 * 市场面向所有使用者,而"能否绑到这个 Agent"由安装动作自身的写校验决定。
 * 发布/审核端点不在本控制器内(见 {@link SkillMarketService} 的范围说明)。</p>
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/skill-market")
public class SkillMarketController {

    private final SkillMarketService skillMarketService;

    /** 浏览平台共享技能 */
    @SaCheckLogin
    @GetMapping("/list")
    public R<PageResult<AgentSkillVo>> list(@RequestParam(required = false) String keyword,
                                            @Validated PageQuery pageQuery) {
        return R.ok(skillMarketService.browse(keyword, pageQuery));
    }

    /** 共享技能详情 */
    @SaCheckLogin
    @GetMapping("/{id}")
    public R<AgentSkillVo> detail(@NotNull(message = "技能ID不能为空") @PathVariable Long id) {
        return R.ok(skillMarketService.detail(id));
    }

    /** 绑定到我的 Agent */
    @SaCheckLogin
    @Log(title = "技能市场", businessType = BusinessType.OTHER)
    @PostMapping("/{skillId}/install/{agentId}")
    public R<Void> install(@NotNull(message = "技能ID不能为空") @PathVariable Long skillId,
                           @NotNull(message = "AgentID不能为空") @PathVariable Long agentId) {
        return skillMarketService.install(skillId, agentId) ? R.ok() : R.fail("安装失败");
    }
}
