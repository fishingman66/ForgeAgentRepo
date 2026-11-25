package com.forgeagent.customerservice.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.domain.CsSeatSkill;
import com.forgeagent.customerservice.domain.CsSkill;
import com.forgeagent.customerservice.domain.CsSkillGroup;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import com.forgeagent.customerservice.service.AdminWorkspaceScope;
import com.forgeagent.customerservice.service.CsSkillService;
import com.forgeagent.customerservice.service.CsWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 技能管理(P1-1 管理界面):技能目录 / 技能组 / 坐席技能等级。
 *
 * <p>workspaceId 一律服务端推导;坐席技能保存走 CsSkillService 全量覆盖 + 投影回 CsSeat.skills。</p>
 */
@Validated
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@ConditionalOnCustomerServiceEnabled
@RequestMapping("/agent/customerservice/skills")
public class CsSkillController {

    private final CsSkillService skillService;
    private final CsWorkspaceResolver workspaceResolver;
    private final CsSeatMapper seatMapper;
    private final AdminWorkspaceScope scope;

    public record SkillReq(String skillCode, String name, String status, Long workspaceId) {
    }

    public record GroupReq(String groupCode, String name, List<String> skillCodes, String matchType, Long workspaceId) {
    }

    public record SeatSkillsReq(Long seatId, Map<String, Integer> ranks, Long workspaceId) {
    }

    private Long listWs(Long requested) {
        return scope.resolveForList(requested);
    }

    private Long writeWs(Long requested) {
        Long ws = scope.resolve(requested);
        if (ws == null) {
            throw new ServiceException("超级管理员请先选择要操作的空间");
        }
        return ws;
    }

    @GetMapping("/workspaces")
    public R<List<Long>> workspaces() {
        return R.ok(scope.availableWorkspaces());
    }

    // ---------------- 技能目录 ----------------
    @GetMapping("/catalog")
    public R<List<CsSkill>> catalog(@RequestParam(required = false) Long workspaceId) {
        return R.ok(skillService.listSkills(listWs(workspaceId)));
    }

    @PostMapping("/catalog")
    public R<CsSkill> saveSkill(@Validated @RequestBody SkillReq req) {
        return R.ok(skillService.saveSkill(writeWs(req.workspaceId()), req.skillCode(), req.name(), req.status()));
    }

    @DeleteMapping("/catalog/{id}")
    public R<Boolean> deleteSkill(@PathVariable Long id,
                                  @RequestParam(required = false) Long workspaceId) {
        return R.ok(skillService.deleteSkill(writeWs(workspaceId), id));
    }

    // ---------------- 技能组 ----------------
    @GetMapping("/groups")
    public R<List<CsSkillGroup>> groups(@RequestParam(required = false) Long workspaceId) {
        return R.ok(skillService.listGroups(listWs(workspaceId)));
    }

    @PostMapping("/groups")
    public R<Void> saveGroup(@Validated @RequestBody GroupReq req) {
        skillService.saveGroup(writeWs(req.workspaceId()), req.groupCode(), req.name(), req.skillCodes(), req.matchType());
        return R.ok();
    }

    @DeleteMapping("/groups/{id}")
    public R<Boolean> deleteGroup(@PathVariable Long id,
                                  @RequestParam(required = false) Long workspaceId) {
        return R.ok(skillService.deleteGroup(writeWs(workspaceId), id));
    }

    // ---------------- 坐席技能 ----------------
    @GetMapping("/seats")
    public R<List<CsSeat>> seats(@RequestParam(required = false) Long workspaceId) {
        return R.ok(seatMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsSeat>()
                .eq(CsSeat::getWorkspaceId, listWs(workspaceId))
                .orderByAsc(CsSeat::getId)));
    }

    @GetMapping("/seat/{seatId}")
    public R<List<CsSeatSkill>> seatSkills(@PathVariable Long seatId,
                                          @RequestParam(required = false) Long workspaceId) {
        Long ws = writeWs(workspaceId);
        CsSeat seat = seatMapper.selectById(seatId);
        if (seat == null || !ws.equals(seat.getWorkspaceId())) {
            throw new ServiceException("无权操作该坐席或坐席不存在");
        }
        Map<String, Integer> ranks = skillService.skillRanks(ws, seatId);
        List<CsSeatSkill> result = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : ranks.entrySet()) {
            CsSeatSkill row = new CsSeatSkill();
            row.setWorkspaceId(ws);
            row.setSeatId(seatId);
            row.setSkillCode(e.getKey());
            row.setRank(e.getValue());
            result.add(row);
        }
        return R.ok(result);
    }

    @PostMapping("/seat")
    public R<Void> saveSeatSkills(@Validated @RequestBody SeatSkillsReq req) {
        Long ws = writeWs(req.workspaceId());
        CsSeat seat = req.seatId() == null ? null : seatMapper.selectById(req.seatId());
        if (seat == null || !ws.equals(seat.getWorkspaceId())) {
            throw new ServiceException("无权操作该坐席或坐席不存在");
        }
        skillService.saveSeatSkills(ws, req.seatId(), req.ranks());
        return R.ok();
    }
}
