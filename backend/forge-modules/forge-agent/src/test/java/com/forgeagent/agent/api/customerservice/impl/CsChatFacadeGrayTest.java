package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.agent.service.run.RunCancelRegistry;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * D1 灰度判定口径(CsChatFacadeImpl.decidePinnedVersion)。
 *
 * <p>锁四件事:①rollout_percent=0 全走现行版;②=100 全锁发布版本;
 * ③中间值按 hash(visitorId) 稳定分流(同访客恒定);④无发布版本/异常降级现行版。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsChatFacadeGrayTest {

    @Mock private IAgentChatService chatService;
    @Mock private RunCancelRegistry runCancel;
    @Mock private AgentInfoMapper agentInfoMapper;

    private CsChatFacadeImpl facade() {
        return new CsChatFacadeImpl(chatService, runCancel, agentInfoMapper);
    }

    private static AgentInfo agent(int rollout, Integer publishedVersion) {
        AgentInfo a = new AgentInfo();
        a.setRolloutPercent(rollout);
        a.setPublishedVersion(publishedVersion);
        return a;
    }

    @Test
    void grayDisabledReturnsNull() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent(0, 5));
        assertNull(facade().decidePinnedVersion(9L, 12345L));
    }

    @Test
    void fullRolloutPinsPublishedVersion() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent(100, 5));
        assertEquals(5, facade().decidePinnedVersion(9L, 12345L));
    }

    @Test
    void defaultRolloutWhenNullPinsPublishedVersion() {
        AgentInfo a = agent(0, 5);
        a.setRolloutPercent(null); // DDL 默认 100,但历史行可能为 null
        when(agentInfoMapper.selectById(9L)).thenReturn(a);
        assertEquals(5, facade().decidePinnedVersion(9L, 12345L));
    }

    @Test
    void partialRolloutIsStablePerVisitor() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent(50, 7));
        Integer first = facade().decidePinnedVersion(9L, 8888L);
        Integer second = facade().decidePinnedVersion(9L, 8888L);
        // 同访客两次判定结果必须一致(会话锁版本前提),且取值只可能是锁版或现行版
        assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertTrue(first == null || first == 7);
    }

    @Test
    void partialRolloutSplitsVisitors() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent(50, 7));
        int hit = 0;
        for (long visitorId = 1; visitorId <= 200; visitorId++) {
            if (facade().decidePinnedVersion(9L, visitorId) != null) {
                hit++;
            }
        }
        // 50% 放量:200 个访客应有相当比例命中,但不是全部也不是零
        org.junit.jupiter.api.Assertions.assertTrue(hit > 60 && hit < 140, "实际命中=" + hit);
    }

    @Test
    void neverPublishedReturnsNull() {
        when(agentInfoMapper.selectById(9L)).thenReturn(agent(100, null));
        assertNull(facade().decidePinnedVersion(9L, 12345L));
    }

    @Test
    void missingAgentReturnsNull() {
        when(agentInfoMapper.selectById(9L)).thenReturn(null);
        assertNull(facade().decidePinnedVersion(9L, 12345L));
    }

    @Test
    void queryExceptionFailsSafe() {
        when(agentInfoMapper.selectById(9L)).thenThrow(new RuntimeException("db down"));
        assertNull(facade().decidePinnedVersion(9L, 12345L));
    }

    @Test
    void nullInputsReturnNull() {
        assertNull(facade().decidePinnedVersion(null, 1L));
        assertNull(facade().decidePinnedVersion(9L, null));
    }
}
