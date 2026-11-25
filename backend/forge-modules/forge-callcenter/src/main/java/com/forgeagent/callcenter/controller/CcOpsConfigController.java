package com.forgeagent.callcenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.*;
import com.forgeagent.callcenter.mapper.*;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.service.AdminWorkspaceScope;
import com.forgeagent.customerservice.service.CsWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;

/**
 * 呼叫中心运营配置管理(P0~P2 管理界面)。
 *
 * <p><b>workspace 隔离</b>:所有列表/保存一律用服务端推导的 workspaceId,不信任入参;
 * 日志与留痕表(状态日志/话单补偿)只提供只读列表,禁止人工增删改。
 * 跨空间 id 一律按"不存在"拒绝,不泄露归属。</p>
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@ConditionalOnCallcenterEnabled
@RequestMapping("/agent/customerservice/cc-ops/config")
public class CcOpsConfigController {

    private final CsOverflowConfigMapper overflowMapper;
    private final CsNumberPoolMapper numberPoolMapper;
    private final CsBlackPhoneMapper blackPhoneMapper;
    private final CsDidRouteMapper didRouteMapper;
    private final CsEnterpriseMapper enterpriseMapper;
    private final CsAgentStateLogMapper stateLogMapper;
    private final CsCdrCompensationMapper cdrCompensationMapper;
    private final CsWorkspaceResolver workspaceResolver;
    private final AdminWorkspaceScope scope;

    /** 通用保存请求(字段按资源解释;workspaceId 绝不由前端提供)。 */
    public record SaveReq(Long id, String name, String triggerType, Integer threshold,
                          String action, String actionArg, String enabled,
                          String number, Integer type, String channelCode, String trunkRef,
                          String numPrefix, Integer callDirection, String schedule,
                          String callbackUrl, String reason, String routeNum, String target,
                          String callerPrefix, String calledPrefix, String description,
                          String status, Integer seatLimit, Integer groupLimit,
                          Integer channelLimit, Integer concurrentLimit, Integer budgetMinutes,
                          Long workspaceId) {
    }

    /** 普通用户忽略前端传参;超管可选空间(null=列表看全部)。 */
    private Long listWs(Long requested) {
        return scope.resolveForList(requested);
    }

    /** 写操作空间:超管必须显式选择,否则拒绝(避免写到 -1/未知空间)。 */
    private Long writeWs(Long requested) {
        Long ws = scope.resolve(requested);
        if (ws == null || ws == CsWorkspaceResolver.EMPTY_WORKSPACE) {
            throw new ServiceException("超级管理员请先选择要操作的空间");
        }
        return ws;
    }

    /** 候选空间(仅超管有值)。 */
    @GetMapping("/workspaces")
    public R<List<Long>> workspaces() {
        return R.ok(scope.availableWorkspaces());
    }

    // ---------------- 溢出策略 ----------------
    @GetMapping("/overflow")
    public R<List<CsOverflowConfig>> listOverflow(@RequestParam(required = false) Long workspaceId) {
        return R.ok(overflowMapper.selectList(new LambdaQueryWrapper<CsOverflowConfig>()
            .eq(CsOverflowConfig::getWorkspaceId, listWs(workspaceId))));
    }

    @PostMapping("/overflow")
    public R<CsOverflowConfig> saveOverflow(@Validated @RequestBody SaveReq req) {
        Long ws = writeWs(req.workspaceId());
        CsOverflowConfig row = req.id() == null ? new CsOverflowConfig()
            : requireOwned(overflowMapper, req.id(), CsOverflowConfig.class, ws);
        row.setWorkspaceId(ws);
        row.setName(req.name());
        row.setTriggerType(req.triggerType());
        row.setThreshold(req.threshold());
        row.setAction(req.action());
        row.setActionArg(req.actionArg());
        row.setEnabled(req.enabled() == null ? "0" : req.enabled());
        row.setUpdateTime(new Date());
        if (req.id() == null) {
            row.setCreateTime(new Date());
            overflowMapper.insert(row);
        } else {
            overflowMapper.updateById(row);
        }
        return R.ok(row);
    }

    @DeleteMapping("/overflow/{id}")
    public R<Boolean> deleteOverflow(@PathVariable Long id,
                                     @RequestParam(required = false) Long workspaceId) {
        return R.ok(removeOwned(overflowMapper, id, CsOverflowConfig.class, writeWs(workspaceId)));
    }

    // ---------------- 号码池 ----------------
    @GetMapping("/number-pool")
    public R<List<CsNumberPool>> listNumbers(@RequestParam(required = false) Long workspaceId) {
        return R.ok(numberPoolMapper.selectList(new LambdaQueryWrapper<CsNumberPool>()
            .eq(CsNumberPool::getWorkspaceId, listWs(workspaceId))));
    }

    @PostMapping("/number-pool")
    public R<CsNumberPool> saveNumber(@Validated @RequestBody SaveReq req) {
        Long ws = writeWs(req.workspaceId());
        CsNumberPool row = req.id() == null ? new CsNumberPool()
            : requireOwned(numberPoolMapper, req.id(), CsNumberPool.class, ws);
        row.setWorkspaceId(ws);
        row.setNumber(req.number());
        row.setType(req.type() == null ? 1 : req.type());
        row.setChannelCode(req.channelCode());
        row.setTrunkRef(req.trunkRef());
        row.setStatus(req.status() == null ? "0" : req.status());
        row.setDescription(req.description());
        row.setUpdateTime(new Date());
        if (req.id() == null) {
            row.setCreateTime(new Date());
            numberPoolMapper.insert(row);
        } else {
            numberPoolMapper.updateById(row);
        }
        return R.ok(row);
    }

    @DeleteMapping("/number-pool/{id}")
    public R<Boolean> deleteNumber(@PathVariable Long id,
                                   @RequestParam(required = false) Long workspaceId) {
        return R.ok(removeOwned(numberPoolMapper, id, CsNumberPool.class, writeWs(workspaceId)));
    }

    // ---------------- 黑名单 ----------------
    @GetMapping("/blacklist")
    public R<List<CsBlackPhone>> listBlacklist(@RequestParam(required = false) Long workspaceId) {
        return R.ok(blackPhoneMapper.selectList(new LambdaQueryWrapper<CsBlackPhone>()
            .eq(CsBlackPhone::getWorkspaceId, listWs(workspaceId))));
    }

    @PostMapping("/blacklist")
    public R<CsBlackPhone> saveBlacklist(@Validated @RequestBody SaveReq req) {
        Long ws = writeWs(req.workspaceId());
        CsBlackPhone row = req.id() == null ? new CsBlackPhone()
            : requireOwned(blackPhoneMapper, req.id(), CsBlackPhone.class, ws);
        row.setWorkspaceId(ws);
        row.setNumPrefix(req.numPrefix());
        row.setCallDirection(req.callDirection() == null ? 2 : req.callDirection());
        row.setSchedule(req.schedule());
        row.setCallbackUrl(req.callbackUrl());
        row.setReason(req.reason());
        row.setStatus(req.status() == null ? "0" : req.status());
        row.setUpdateTime(new Date());
        if (req.id() == null) {
            row.setCreateTime(new Date());
            blackPhoneMapper.insert(row);
        } else {
            blackPhoneMapper.updateById(row);
        }
        return R.ok(row);
    }

    @DeleteMapping("/blacklist/{id}")
    public R<Boolean> deleteBlacklist(@PathVariable Long id,
                                      @RequestParam(required = false) Long workspaceId) {
        return R.ok(removeOwned(blackPhoneMapper, id, CsBlackPhone.class, writeWs(workspaceId)));
    }

    // ---------------- DID 字冠路由 ----------------
    @GetMapping("/did-routes")
    public R<List<CsDidRoute>> listDidRoutes(@RequestParam(required = false) Long workspaceId) {
        return R.ok(didRouteMapper.selectList(new LambdaQueryWrapper<CsDidRoute>()
            .eq(CsDidRoute::getWorkspaceId, listWs(workspaceId))));
    }

    @PostMapping("/did-routes")
    public R<CsDidRoute> saveDidRoute(@Validated @RequestBody SaveReq req) {
        Long ws = writeWs(req.workspaceId());
        CsDidRoute row = req.id() == null ? new CsDidRoute()
            : requireOwned(didRouteMapper, req.id(), CsDidRoute.class, ws);
        row.setWorkspaceId(ws);
        row.setRouteNum(req.routeNum());
        row.setTarget(req.target());
        row.setCallerPrefix(req.callerPrefix());
        row.setCalledPrefix(req.calledPrefix());
        row.setStatus(req.status() == null ? "0" : req.status());
        row.setDescription(req.description());
        row.setUpdateTime(new Date());
        if (req.id() == null) {
            row.setCreateTime(new Date());
            didRouteMapper.insert(row);
        } else {
            didRouteMapper.updateById(row);
        }
        return R.ok(row);
    }

    @DeleteMapping("/did-routes/{id}")
    public R<Boolean> deleteDidRoute(@PathVariable Long id,
                                     @RequestParam(required = false) Long workspaceId) {
        return R.ok(removeOwned(didRouteMapper, id, CsDidRoute.class, writeWs(workspaceId)));
    }

    // ---------------- 企业配额(按 workspace 归属) ----------------
    @GetMapping("/enterprise")
    public R<CsEnterprise> enterprise(@RequestParam(required = false) Long workspaceId) {
        return R.ok(enterpriseMapper.selectOne(new LambdaQueryWrapper<CsEnterprise>()
            .eq(CsEnterprise::getWorkspaceId, listWs(workspaceId)).last("limit 1")));
    }

    @PostMapping("/enterprise")
    public R<CsEnterprise> saveEnterprise(@Validated @RequestBody SaveReq req) {
        Long ws = writeWs(req.workspaceId());
        CsEnterprise row = enterpriseMapper.selectOne(new LambdaQueryWrapper<CsEnterprise>()
            .eq(CsEnterprise::getWorkspaceId, ws).last("limit 1"));
        if (row == null) {
            row = new CsEnterprise();
            row.setWorkspaceId(ws);
            row.setCode("WS-" + ws);
            row.setCreateTime(new Date());
        }
        row.setName(req.name());
        row.setStatus(req.status() == null ? "0" : req.status());
        row.setSeatLimit(req.seatLimit());
        row.setGroupLimit(req.groupLimit());
        row.setChannelLimit(req.channelLimit());
        row.setConcurrentLimit(req.concurrentLimit());
        row.setBudgetMinutes(req.budgetMinutes());
        row.setUpdateTime(new Date());
        if (row.getId() == null) {
            enterpriseMapper.insert(row);
        } else {
            enterpriseMapper.updateById(row);
        }
        return R.ok(row);
    }

    // ---------------- 只读:状态日志 / 话单补偿 ----------------
    @GetMapping("/state-logs")
    public R<List<CsAgentStateLog>> stateLogs(@RequestParam(required = false) Long workspaceId) {
        return R.ok(stateLogMapper.selectList(new LambdaQueryWrapper<CsAgentStateLog>()
            .eq(CsAgentStateLog::getWorkspaceId, listWs(workspaceId))
            .orderByDesc(CsAgentStateLog::getEventTimeMs).last("limit 200")));
    }

    @GetMapping("/cdr-compensations")
    public R<List<CsCdrCompensation>> cdrCompensations(@RequestParam(required = false) Long workspaceId) {
        return R.ok(cdrCompensationMapper.selectList(new LambdaQueryWrapper<CsCdrCompensation>()
            .eq(CsCdrCompensation::getWorkspaceId, listWs(workspaceId))
            .orderByDesc(CsCdrCompensation::getId).last("limit 200")));
    }

    /** 跨空间 id 直接拒绝(不返回 null 让上层 NPE,也不泄露归属)。 */
    private <T> T requireOwned(BaseMapper<T> mapper, Long id, Class<T> type, Long ws) {
        T row = id == null ? null : mapper.selectById(id);
        if (row == null) {
            throw new ServiceException("无权操作该记录或记录不存在");
        }
        Object actual = workspaceOf(row, type);
        if (ws == null || !ws.equals(actual)) {
            throw new ServiceException("无权操作该记录或记录不存在");
        }
        return row;
    }

    private <T> boolean removeOwned(BaseMapper<T> mapper, Long id, Class<T> type, Long ws) {
        requireOwned(mapper, id, type, ws); // 越权先拒绝
        return mapper.deleteById(id) > 0;
    }

    private static <T> Object workspaceOf(T row, Class<T> type) {
        try {
            Method m = type.getMethod("getWorkspaceId");
            return m.invoke(row);
        } catch (Exception e) {
            return null;
        }
    }
}
