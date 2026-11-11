package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCallSeatState;
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
        String readyState = event.str("readyState");
        if (readyState != null && !readyState.isBlank()) {
            state.setReadyState(readyState);
        }
        state.setPauseReason(event.str("pauseReason"));
        state.setUpdateTime(now);
        if (firstSeen) {
            seatStateMapper.insert(state);
        } else {
            seatStateMapper.updateById(state);
        }
    }
}
