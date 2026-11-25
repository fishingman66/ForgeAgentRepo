package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.acd.assign.SkillMatcher;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.domain.CsSeatSkill;
import com.forgeagent.customerservice.domain.CsSkill;
import com.forgeagent.customerservice.domain.CsSkillGroup;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import com.forgeagent.customerservice.mapper.CsSeatSkillMapper;
import com.forgeagent.customerservice.mapper.CsSkillGroupMapper;
import com.forgeagent.customerservice.mapper.CsSkillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化技能服务(P1-1):技能目录/坐席技能/技能组的写入与等级读。
 *
 * <p><b>零回退兼容</b>:P0-2 的 SkillMatcher 仍按 CsSeat.skills 逗号串读取;本服务新增
 * 结构化表后,在写入时把 `skill:rank` 串投影回 CsSeat.skills,老读路径无需改造即可拿到真实等级。
 * 历史数据(新表为空)时 skillRanks() 返回空 Map,调用方回退解析逗号串(rank 恒 1,与改造前一致)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsSkillService {

    /** 单组技能上限(参考 ai-call-center GroupServiceImpl:71-73)。 */
    public static final int MAX_SKILLS_PER_GROUP = 10;

    private final CsSkillMapper skillMapper;
    private final CsSeatSkillMapper seatSkillMapper;
    private final CsSkillGroupMapper groupMapper;
    private final CsSeatMapper seatMapper;

    /** 技能目录列表(空间隔离)。 */
    public java.util.List<CsSkill> listSkills(Long workspaceId) {
        if (workspaceId == null) {
            return java.util.List.of();
        }
        return skillMapper.selectList(new LambdaQueryWrapper<CsSkill>()
            .eq(CsSkill::getWorkspaceId, workspaceId)
            .orderByAsc(CsSkill::getSkillCode));
    }

    /** 保存技能目录项:按 workspace+skillCode 查重,新增或更新。 */
    public CsSkill saveSkill(Long workspaceId, String skillCode, String name, String status) {
        if (workspaceId == null || skillCode == null || skillCode.isBlank()) {
            throw new ServiceException("workspace / 技能编码不能为空");
        }
        Date now = new Date();
        CsSkill existing = skillMapper.selectOne(new LambdaQueryWrapper<CsSkill>()
            .eq(CsSkill::getWorkspaceId, workspaceId)
            .eq(CsSkill::getSkillCode, skillCode.trim()).last("limit 1"));
        CsSkill row = existing == null ? new CsSkill() : existing;
        row.setWorkspaceId(workspaceId);
        row.setSkillCode(skillCode.trim());
        row.setName(name);
        row.setStatus(status == null || status.isBlank() ? "0" : status);
        row.setUpdateTime(now);
        if (existing == null) {
            row.setCreateTime(now);
            skillMapper.insert(row);
        } else {
            skillMapper.updateById(row);
        }
        return row;
    }

    /** 删除技能目录项:仅删本空间,同时清理坐席技能关联(防残留)。 */
    public boolean deleteSkill(Long workspaceId, Long id) {
        if (workspaceId == null || id == null) {
            return false;
        }
        CsSkill row = skillMapper.selectById(id);
        if (row == null || !workspaceId.equals(row.getWorkspaceId())) {
            return false;
        }
        seatSkillMapper.delete(new LambdaQueryWrapper<CsSeatSkill>()
            .eq(CsSeatSkill::getWorkspaceId, workspaceId)
            .eq(CsSeatSkill::getSkillCode, row.getSkillCode()));
        return skillMapper.deleteById(id) > 0;
    }

    /** 技能组列表(空间隔离)。 */
    public java.util.List<CsSkillGroup> listGroups(Long workspaceId) {
        if (workspaceId == null) {
            return java.util.List.of();
        }
        return groupMapper.selectList(new LambdaQueryWrapper<CsSkillGroup>()
            .eq(CsSkillGroup::getWorkspaceId, workspaceId)
            .orderByAsc(CsSkillGroup::getGroupCode));
    }

    /** 删除技能组:仅删本空间。 */
    public boolean deleteGroup(Long workspaceId, Long id) {
        if (workspaceId == null || id == null) {
            return false;
        }
        CsSkillGroup row = groupMapper.selectById(id);
        if (row == null || !workspaceId.equals(row.getWorkspaceId())) {
            return false;
        }
        return groupMapper.deleteById(id) > 0;
    }

    /** 保存坐席技能(全量覆盖):写关联表 + 投影回 CsSeat.skills。 */
    public void saveSeatSkills(Long workspaceId, Long seatId, Map<String, Integer> ranks) {
        if (workspaceId == null || seatId == null) {
            throw new ServiceException("workspaceId / seatId 不能为空");
        }
        Date now = new Date();
        // 全量覆盖:删旧插新,唯一键 (ws,seat,skill) 防重
        seatSkillMapper.delete(new LambdaQueryWrapper<CsSeatSkill>()
            .eq(CsSeatSkill::getWorkspaceId, workspaceId)
            .eq(CsSeatSkill::getSeatId, seatId));
        if (ranks != null) {
            for (Map.Entry<String, Integer> e : ranks.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    continue;
                }
                CsSeatSkill row = new CsSeatSkill();
                row.setWorkspaceId(workspaceId);
                row.setSeatId(seatId);
                row.setSkillCode(e.getKey().trim());
                row.setRank(e.getValue() == null ? 1 : e.getValue());
                row.setCreateTime(now);
                row.setUpdateTime(now);
                seatSkillMapper.insert(row);
            }
        }
        projectToSeat(workspaceId, seatId, ranks, now);
    }

    /** 读坐席技能等级:新表优先;无关联行返回空 Map(调用方回退逗号串)。 */
    public Map<String, Integer> skillRanks(Long workspaceId, Long seatId) {
        if (workspaceId == null || seatId == null) {
            return Map.of();
        }
        List<CsSeatSkill> rows = seatSkillMapper.selectList(new LambdaQueryWrapper<CsSeatSkill>()
            .eq(CsSeatSkill::getWorkspaceId, workspaceId)
            .eq(CsSeatSkill::getSeatId, seatId));
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (CsSeatSkill r : rows) {
            result.put(r.getSkillCode(), r.getRank() == null ? 1 : r.getRank());
        }
        return result;
    }

    /** 保存技能组:单组技能 ≤10 校验;matchType 仅 ANY/ALL。 */
    public void saveGroup(Long workspaceId, String groupCode, String name,
                          List<String> skillCodes, String matchType) {
        if (workspaceId == null || groupCode == null || groupCode.isBlank()) {
            throw new ServiceException("workspaceId / groupCode 不能为空");
        }
        if (skillCodes != null && skillCodes.size() > MAX_SKILLS_PER_GROUP) {
            throw new ServiceException("单技能组技能数不能超过 " + MAX_SKILLS_PER_GROUP);
        }
        String type = matchType == null || matchType.isBlank() ? "ANY" : matchType.trim().toUpperCase();
        if (!"ANY".equals(type) && !"ALL".equals(type)) {
            throw new ServiceException("matchType 仅允许 ANY / ALL");
        }
        String codes = skillCodes == null ? "" : String.join(",", skillCodes);
        Date now = new Date();
        CsSkillGroup existing = groupMapper.selectOne(new LambdaQueryWrapper<CsSkillGroup>()
            .eq(CsSkillGroup::getWorkspaceId, workspaceId)
            .eq(CsSkillGroup::getGroupCode, groupCode).last("limit 1"));
        CsSkillGroup row = existing == null ? new CsSkillGroup() : existing;
        row.setWorkspaceId(workspaceId);
        row.setGroupCode(groupCode);
        row.setName(name);
        row.setSkillCodes(codes);
        row.setMatchType(type);
        row.setStatus("0");
        row.setUpdateTime(now);
        if (existing == null) {
            row.setCreateTime(now);
            groupMapper.insert(row);
        } else {
            groupMapper.updateById(row);
        }
    }

    /** 投影回 CsSeat.skills:`skill:rank` 逗号串,老读路径 SkillMatcher.parse 已支持该语法。 */
    private void projectToSeat(Long workspaceId, Long seatId,
                               Map<String, Integer> ranks, Date now) {
        CsSeat seat = seatMapper.selectById(seatId);
        if (seat == null || !workspaceId.equals(seat.getWorkspaceId())) {
            log.warn("[CS] 技能投影跳过:坐席不存在或跨空间 seatId={} ws={}", seatId, workspaceId);
            return;
        }
        List<String> parts = new ArrayList<>();
        if (ranks != null) {
            for (Map.Entry<String, Integer> e : ranks.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank()) {
                    parts.add(e.getKey().trim() + ":" + (e.getValue() == null ? 1 : e.getValue()));
                }
            }
        }
        seat.setSkills(String.join(",", parts));
        seat.setUpdateTime(now);
        seatMapper.updateById(seat);
    }
}
