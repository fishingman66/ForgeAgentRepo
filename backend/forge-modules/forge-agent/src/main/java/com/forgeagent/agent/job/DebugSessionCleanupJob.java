package com.forgeagent.agent.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 调试会话定时清理(UX-01):临时会话 30 分钟无活动自动删除,避免脏数据堆积
 *
 * <p>清理范围:agent_session.temp=1 且 last_msg_time 早于阈值(无消息时按 create_time 兜底);
 * 关联消息同步删除。cron 可通过 {@code agent.debug-session.cleanup-cron} 覆盖,
 * 保留分钟可通过 {@code agent.debug-session.ttl-minutes} 覆盖。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class DebugSessionCleanupJob {

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;

    /**
     * 每 10 分钟扫描一次,删除 30 分钟无活动的调试会话
     */
    @Scheduled(cron = "${agent.debug-session.cleanup-cron:0 */10 * * * ?}")
    public void cleanup() {
        int ttlMinutes = Integer.parseInt(System.getProperty("agent.debug-session.ttl-minutes", "30"));
        Date threshold = new Date(System.currentTimeMillis() - ttlMinutes * 60_000L);
        List<AgentSession> stale = sessionMapper.selectList(new LambdaQueryWrapper<AgentSession>()
            .eq(AgentSession::getTemp, 1)
            .and(w -> w.lt(AgentSession::getLastMsgTime, threshold)
                .or().and(w2 -> w2.isNull(AgentSession::getLastMsgTime)
                    .lt(AgentSession::getCreateTime, threshold)))
            .last("limit 500"));
        if (stale.isEmpty()) {
            return;
        }
        int ok = 0;
        for (AgentSession s : stale) {
            try {
                messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getSessionId, s.getId()));
                sessionMapper.deleteById(s.getId());
                ok++;
            } catch (Exception e) {
                log.warn("[DebugSessionCleanup] 删除调试会话失败 id={}: {}", s.getId(), e.getMessage());
            }
        }
        log.info("[DebugSessionCleanup] 本次清理调试会话 {} 个(阈值 {} 分钟)", ok, ttlMinutes);
    }
}
