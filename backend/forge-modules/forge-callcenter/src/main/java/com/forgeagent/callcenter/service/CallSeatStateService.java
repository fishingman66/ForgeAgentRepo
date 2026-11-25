package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCallSeatState;
import com.forgeagent.callcenter.domain.SeatStates;
import com.forgeagent.callcenter.domain.CsAgentStateLog;
import com.forgeagent.callcenter.gateway.CsVoiceEvent;
import com.forgeagent.callcenter.mapper.CsCallSeatStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 电话坐席状态(V1):按 AICC 工号 upsert;ACD 按 workNo 关联,不按分机硬绑定(方案 v2 第十二章)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallSeatStateService {

    private final CsCallSeatStateMapper seatStateMapper;
    /** P1-2 状态轨迹;ObjectProvider 保持既有测试构造器签名不变。 */
    private final org.springframework.beans.factory.ObjectProvider<AgentStateLogService> logServiceProvider;

    public void apply(Long workspaceId, CsVoiceEvent event) {
        String workNo = event.workNo() == null || event.workNo().isBlank()
            ? event.str("workNo") : event.workNo();
        if (workNo == null || workNo.isBlank()) {
            log.warn("[CC] seat_state 缺少 workNo,忽略");
            return;
        }
        Date now = new Date();
        CsCallSeatState state = seatStateMapper.selectOne(new LambdaQueryWrapper<CsCallSeatState>()
            .eq(CsCallSeatState::getWorkNo, workNo).last("limit 1"));
        boolean firstSeen = state == null;
        if (firstSeen) {
            state = new CsCallSeatState();
            state.setWorkNo(workNo);
            state.setWorkspaceId(workspaceId);
            state.setLoginAt(now);
            state.setCreateTime(now);
        }
        String readyState = SeatStates.normalize(TransferTimelineService.seatState(event));
        String before = firstSeen ? null : state.getReadyState();
        if (readyState != null && !readyState.isBlank()) {
            // 单调防回退:新态 rank 低于已知态时忽略(与通话状态机同口径,独立实现)
            if (!firstSeen && !SeatStates.advances(before, readyState)) {
                log.debug("[CC] 坐席状态回退忽略 workNo={} {} → {}", workNo, before, readyState);
                return;
            }
            state.setReadyState(readyState);
        }
        state.setPauseReason(event.str("pauseReason"));
        state.setUpdateTime(now);
        if (firstSeen) {
            seatStateMapper.insert(state);
        } else {
            seatStateMapper.updateById(state);
        }
        recordLog(workspaceId, workNo, state, before, event, now);
    }

    /** P1-2:写状态轨迹(开关关闭/服务缺失时静默跳过,不影响主链路)。 */
    private void recordLog(Long workspaceId, String workNo, CsCallSeatState state,
                           String before, CsVoiceEvent event, Date now) {
        AgentStateLogService logService = logServiceProvider.getIfAvailable();
        if (logService == null || !logService.enabled() || state.getReadyState() == null) {
            return;
        }
        long eventTime = event.eventGenTime() > 0 ? event.eventGenTime() : now.getTime();
        // 前态起始时间:优先取最近一条轨迹的事件时间;无轨迹则用本次事件时间(时长=0)
        Long since = null;
        CsAgentStateLog latest = logService.latest(workNo);
        if (latest != null && latest.getEventTimeMs() != null) {
            since = latest.getEventTimeMs();
        }
        logService.record(workspaceId, workNo, state.getSeatId(), before,
            state.getReadyState(), since, eventTime);
    }
}
