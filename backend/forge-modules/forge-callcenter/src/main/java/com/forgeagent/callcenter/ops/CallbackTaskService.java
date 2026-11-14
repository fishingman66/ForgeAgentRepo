package com.forgeagent.callcenter.ops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallbackTask;
import com.forgeagent.callcenter.mapper.CsCallbackTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 呼损回拨清单(C8,方案 docs/07 §三 P2):振铃未接通即自动建单,运营回拨后销单。
 *
 * <p>呼损判定只看「有振铃、无接听」——没有 {@code ringAt} 的记录是脏数据或未接通链路,
 * 不进清单(否则清单会被无效单淹没)。</p>
 *
 * <p>幂等:唯一键 {@code call_id},重放/迟到 CDR 都不会重复建单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CallbackTaskService {

    /** 振铃未接。 */
    public static final String REASON_NO_ANSWER = "NO_ANSWER";
    /** 排队放弃(8 态状态机暂无独立态,预留给 C9 真实队列事件)。 */
    public static final String REASON_ABANDONED = "ABANDONED";

    private static final Set<String> STATUSES = Set.of(
        CsCallbackTask.STATUS_PENDING, CsCallbackTask.STATUS_CALLED, CsCallbackTask.STATUS_CLOSED);

    private final CsCallbackTaskMapper taskMapper;

    /**
     * 挂机时判定呼损并建单;返回 true 表示本次新建(便于测试与日志计数)。
     */
    public boolean onHangup(CsCall call) {
        if (call == null || call.getId() == null) {
            return false;
        }
        if (call.getRingAt() == null || call.getAnswerAt() != null) {
            return false;
        }
        try {
            Long existing = taskMapper.selectCount(new LambdaQueryWrapper<CsCallbackTask>()
                .eq(CsCallbackTask::getCallId, call.getId()));
            if (existing != null && existing > 0) {
                return false;
            }
            CsCallbackTask task = new CsCallbackTask();
            task.setCallId(call.getId());
            task.setWorkspaceId(call.getWorkspaceId());
            task.setChannelCode(call.getChannelCode());
            task.setCallerNumber(call.getCallerNumber());
            task.setReason("SEAT_CONNECTED".equals(call.getStatus()) ? REASON_ABANDONED : REASON_NO_ANSWER);
            task.setStatus(CsCallbackTask.STATUS_PENDING);
            task.setAttemptCount(0);
            Date now = new Date();
            task.setCreateTime(now);
            task.setUpdateTime(now);
            taskMapper.insert(task);
            log.info("[CC] 呼损建单 callId={} caller={} reason={}",
                call.getCallId(), call.getCallerNumber(), task.getReason());
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        } catch (Exception e) {
            log.warn("[CC] 呼损建单失败 callId={}: {}", call.getCallId(), e.getMessage());
            return false;
        }
    }

    /** 按空间 + 状态列出回拨单(不传状态则全部);上限 200,按下单时间倒序。 */
    public List<CsCallbackTask> list(Long workspaceId, String status) {
        LambdaQueryWrapper<CsCallbackTask> wrapper = new LambdaQueryWrapper<CsCallbackTask>()
            .eq(CsCallbackTask::getWorkspaceId, workspaceId)
            .orderByDesc(CsCallbackTask::getId)
            .last("limit 200");
        if (status != null && !status.isBlank()) {
            wrapper.eq(CsCallbackTask::getStatus, status.toUpperCase());
        }
        return taskMapper.selectList(wrapper);
    }

    /**
     * 状态流转:只允许 待回拨→已回拨 / 待回拨→已关闭 / 已回拨→已关闭,
     * 以及「已回拨→已回拨」记录下一次拨打尝试。非法流转抛 {@link IllegalStateException}。
     */
    public CsCallbackTask mark(Long id, String target, Long operatorId, String remark) {
        String to = target == null ? "" : target.trim().toUpperCase();
        if (!STATUSES.contains(to)) {
            throw new IllegalArgumentException("未知状态: " + target);
        }
        CsCallbackTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new IllegalArgumentException("回拨单不存在");
        }
        if (!canTransit(task.getStatus(), to)) {
            throw new IllegalStateException("非法状态流转: " + task.getStatus() + " → " + to);
        }
        task.setStatus(to);
        task.setOperatorId(operatorId);
        task.setRemark(remark);
        if (CsCallbackTask.STATUS_CALLED.equals(to)) {
            task.setAttemptCount((task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1);
            task.setLastAttemptAt(new Date());
        }
        task.setUpdateTime(new Date());
        taskMapper.updateById(task);
        return task;
    }

    /** 合法流转判定(纯函数,单测锁定)。 */
    public static boolean canTransit(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return switch (from) {
            case CsCallbackTask.STATUS_PENDING -> CsCallbackTask.STATUS_CALLED.equals(to)
                || CsCallbackTask.STATUS_CLOSED.equals(to);
            case CsCallbackTask.STATUS_CALLED -> CsCallbackTask.STATUS_CLOSED.equals(to)
                || CsCallbackTask.STATUS_CALLED.equals(to);
            default -> false;
        };
    }
}
