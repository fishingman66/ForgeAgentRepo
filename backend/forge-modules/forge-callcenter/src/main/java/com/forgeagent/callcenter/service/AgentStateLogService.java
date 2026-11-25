package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsAgentStateLog;
import com.forgeagent.callcenter.domain.SeatStates;
import com.forgeagent.callcenter.mapper.CsAgentStateLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 坐席状态轨迹(P1-2):状态变化时记前态/后态/时长,并按 month 归档键落表。
 *
 * <p><b>乱序不回退</b>:事件时间(eventTimeMs)早于当前状态起始时间,或新状态 rank 低于
 * 当前已知状态时,跳过写入(与通话 8 态状态机单调 rank 同口径,但不共用状态机)。
 * 默认关闭(agent.callcenter.agent-state-log-enabled=false)时完全不查库不写入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStateLogService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final CsAgentStateLogMapper mapper;
    private final CallcenterProperties properties;

    /** 是否启用(默认关闭,零回退)。 */
    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getAgentStateLogEnabled());
    }

    /** 归档键:由事件时间确定,跨月事件落各自月份(纯函数,便于单测)。 */
    public static String monthKey(long eventTimeMs) {
        return MONTH.format(Instant.ofEpochMilli(eventTimeMs).atZone(ZoneId.systemDefault()));
    }

    /**
     * 记录一次状态变化。
     *
     * @param workspaceId  空间
     * @param workNo       工号
     * @param seatId       坐席ID(可空)
     * @param beforeState  前态(可空)
     * @param newState     新态
     * @param stateSinceMs 前态起始时间(毫秒),用于算时长
     * @param eventTimeMs  本次事件时间(毫秒)
     * @return 是否实际写入
     */
    public boolean record(Long workspaceId, String workNo, Long seatId, String beforeState,
                          String newState, Long stateSinceMs, long eventTimeMs) {
        if (!enabled() || workNo == null || workNo.isBlank()) {
            return false;
        }
        String normalized = SeatStates.normalize(newState);
        if (normalized == null) {
            return false;
        }
        // 乱序:新状态 rank 低于前态,或事件时间早于前态起始时间 → 不回退、不写
        if (beforeState != null && !SeatStates.advances(beforeState, normalized)) {
            log.debug("[CC] 坐席状态乱序跳过 workNo={} {} → {} ", workNo, beforeState, normalized);
            return false;
        }
        if (stateSinceMs != null && eventTimeMs < stateSinceMs) {
            log.debug("[CC] 坐席状态事件时间倒退跳过 workNo={} event={} since={}",
                workNo, eventTimeMs, stateSinceMs);
            return false;
        }
        try {
            CsAgentStateLog row = new CsAgentStateLog();
            row.setMonth(monthKey(eventTimeMs));
            row.setWorkspaceId(workspaceId);
            row.setWorkNo(workNo);
            row.setSeatId(seatId);
            row.setBeforeState(beforeState == null ? null : SeatStates.normalize(beforeState));
            row.setState(normalized);
            long since = stateSinceMs == null ? eventTimeMs : stateSinceMs;
            row.setDurationSec((int) Math.max(0, (eventTimeMs - since) / 1000));
            row.setEventTimeMs(eventTimeMs);
            row.setCreateTime(new Date());
            mapper.insert(row);
            return true;
        } catch (Exception e) {
            log.warn("[CC] 坐席状态轨迹写入失败 workNo={}: {}", workNo, e.getMessage());
            return false;
        }
    }

    /** 查某工号的最近一条状态(按事件时间倒序),用于取前态与起始时间。 */
    public CsAgentStateLog latest(String workNo) {
        if (!enabled() || workNo == null || workNo.isBlank()) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<CsAgentStateLog>()
            .eq(CsAgentStateLog::getWorkNo, workNo)
            .orderByDesc(CsAgentStateLog::getEventTimeMs)
            .last("limit 1"));
    }
}
