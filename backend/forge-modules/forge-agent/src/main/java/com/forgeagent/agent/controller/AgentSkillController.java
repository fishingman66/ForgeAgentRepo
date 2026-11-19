package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentSkillBo;
import com.forgeagent.agent.domain.vo.AgentSkillBindVo;
import com.forgeagent.agent.domain.vo.AgentSkillVo;
import com.forgeagent.agent.domain.vo.SkillInstallTargetVo;
import com.forgeagent.agent.service.IAgentSkillService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 技能包管理 Controller(08方案 / A1)
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/skill")
public class AgentSkillController extends BaseController {

    private final IAgentSkillService skillService;

    /**
     * 分页查询技能
     */
    @SaCheckPermission("agent:skill:list")
    @GetMapping("/list")
    public R<PageResult<AgentSkillVo>> list(@Validated PageQuery pageQuery, AgentSkillBo bo) {
        return R.ok(skillService.queryPageList(bo, pageQuery));
    }

    /**
     * 技能详情
     */
    @SaCheckPermission("agent:skill:query")
    @GetMapping("/{id}")
    public R<AgentSkillVo> getInfo(@NotNull(message = "主键不能为空")
                                   @PathVariable Long id) {
        return R.ok(skillService.queryById(id));
    }

    /**
     * 新增技能
     */
    @SaCheckPermission("agent:skill:add")
    @Log(title = "技能管理", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentSkillBo bo) {
        return toAjax(skillService.insertByBo(bo));
    }

    /**
     * 修改技能
     */
    @SaCheckPermission("agent:skill:edit")
    @Log(title = "技能管理", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentSkillBo bo) {
        return toAjax(skillService.updateByBo(bo));
    }

    /**
     * 启用/停用技能
     */
    @SaCheckPermission("agent:skill:edit")
    @Log(title = "技能管理", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotNull String status) {
        return toAjax(skillService.changeStatus(id, status));
    }

    /**
     * 删除技能
     */
    @SaCheckPermission("agent:skill:remove")
    @Log(title = "技能管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotNull(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(skillService.deleteByIds(List.of(ids)));
    }

    /**
     * 安装技能到 Agent
     */
    @SaCheckPermission("agent:skill:install")
    @Log(title = "技能管理", businessType = BusinessType.OTHER)
    @PostMapping("/{skillId}/install/{agentId}")
    public R<Void> install(@NotNull(message = "技能ID不能为空") @PathVariable Long skillId,
                           @NotNull(message = "AgentID不能为空") @PathVariable Long agentId) {
        return toAjax(skillService.installToAgent(skillId, agentId));
    }

    /**
     * 从 Agent 卸载技能
     */
    @SaCheckPermission("agent:skill:install")
    @Log(title = "技能管理", businessType = BusinessType.OTHER)
    @PostMapping("/{skillId}/uninstall/{agentId}")
    public R<Void> uninstall(@NotNull(message = "技能ID不能为空") @PathVariable Long skillId,
                             @NotNull(message = "AgentID不能为空") @PathVariable Long agentId) {
        return toAjax(skillService.uninstallFromAgent(skillId, agentId));
    }

    /**
     * 技能视角的安装目标清单(全部已发布应用 + 安装态),供管理页安装/卸载
     */
    @SaCheckPermission("agent:skill:install")
    @GetMapping("/{skillId}/install-targets")
    public R<List<SkillInstallTargetVo>> installTargets(
        @NotNull(message = "技能ID不能为空") @PathVariable Long skillId) {
        return R.ok(skillService.listInstallTargets(skillId));
    }

    /**
     * 应用视角的已装技能清单(含已停用),供 Agent 侧抽屉查看与卸载
     */
    @SaCheckPermission("agent:skill:list")
    @GetMapping("/installed/{agentId}")
    public R<List<AgentSkillBindVo>> installedByAgent(
        @NotNull(message = "AgentID不能为空") @PathVariable Long agentId) {
        return R.ok(skillService.listInstalledSkills(agentId));
    }
}
