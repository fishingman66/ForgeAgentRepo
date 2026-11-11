package com.forgeagent.memory.service.impl;

import com.forgeagent.memory.config.MemoryGovernanceProperties;
import com.forgeagent.memory.domain.AgentMemory;
import com.forgeagent.memory.mapper.AgentMemoryMapper;
import com.forgeagent.memory.mapper.MemoryGovernanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆治理回归(P3 / M4):分批归档、TTL 清扫、配额淘汰选最低分、开关空转。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class MemoryGovernanceServiceTest {

    @Mock
    private MemoryGovernanceMapper governanceMapper;
    @Mock
    private AgentMemoryMapper memoryMapper;

    private MemoryGovernanceProperties properties;
    private MemoryGovernanceService service;

    @BeforeEach
    void setUp() {
        properties = new MemoryGovernanceProperties();
        service = new MemoryGovernanceService(governanceMapper, memoryMapper, properties);
    }

    private static AgentMemory memory(long id, int importance, int hits) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setAgentId(7L);
        m.setUserId(42L);
        m.setImportance(importance);
        m.setHitCount(hits);
        m.setCreateTime(LocalDateTime.now());
        return m;
    }

    @Test
    void archiveRunsInBatchesUntilExhausted() {
        properties.setArchiveRetentionDays(180);
        properties.setArchiveBatch(10);
        when(governanceMapper.archiveMessages(eq(180), eq(10), any())).thenReturn(10, 10, 0);

        long total = service.runArchive();

        assertEquals(20, total);
        verify(governanceMapper, times(2)).deleteArchivedMessages(any());
    }

    @Test
    void archiveStopsAfterPartialBatch() {
        properties.setArchiveRetentionDays(180);
        properties.setArchiveBatch(10);
        when(governanceMapper.archiveMessages(anyInt(), anyInt(), any())).thenReturn(3);

        assertEquals(3, service.runArchive());
        verify(governanceMapper, times(1)).deleteArchivedMessages(any());
    }

    @Test
    void archiveDisabledWhenRetentionZero() {
        properties.setArchiveRetentionDays(0);
        assertEquals(0, service.runOnce().get("archivedMessages"));
        // 只有归档步受 retention 开关;TTL 与配额照常跑(各自有零命中路径)
        verify(governanceMapper, never()).archiveMessages(anyInt(), anyInt(), any());
    }

    @Test
    void ttlSweepPassesImplicitTtlDays() {
        properties.setTtlDays(30);
        properties.setArchiveBatch(10);
        when(governanceMapper.archiveExpiredMemories(eq(30), eq(10), any())).thenReturn(0);

        assertEquals(0, service.runOnce().get("ttlArchived"));
        verify(governanceMapper).archiveExpiredMemories(eq(30), eq(10), any());
    }

    @Test
    void quotaEvictsLowestScoreFirst() {
        when(governanceMapper.countOverQuotaGroups(0)).thenReturn(List.of(Map.of(
            "agentId", 7L, "userId", 42L, "cnt", 12L, "quota", 10)));
        // 三条候选:分数 A < C < B,超配 2 条应淘汰 A 和 C,不能碰最高分的 B
        when(memoryMapper.selectList(any())).thenReturn(List.of(
            memory(1, 9, 0), memory(2, 1, 0), memory(3, 5, 0)));

        long evicted = service.runOnce().get("quotaEvicted");

        assertEquals(2, evicted);
        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(governanceMapper, times(2)).archiveMemory(captor.capture(), eq("QUOTA"));
        assertEquals(List.of(2L, 3L), captor.getAllValues().stream().map(AgentMemory::getId).toList(),
            "先淘汰评分最低的,最新最常用的留到最后");
        InOrder order = inOrder(governanceMapper, memoryMapper);
        order.verify(governanceMapper).archiveMemory(any(), eq("QUOTA"));
        order.verify(memoryMapper).deleteById(anyLong());
    }

    @Test
    void quotaSkippedWhenEffectiveQuotaZero() {
        // agent 未配额 + 全局默认 0 → 不限,不淘汰
        when(governanceMapper.countOverQuotaGroups(0)).thenReturn(List.of(Map.of(
            "agentId", 7L, "userId", 42L, "cnt", 500L, "quota", 0L)));

        assertEquals(0, service.runOnce().get("quotaEvicted"));
        verify(memoryMapper, never()).selectList(any());
    }

    @Test
    void evictBeforeInsertOnlyWhenReallyOver() {
        when(memoryMapper.selectAgentQuota(7L)).thenReturn(10);
        when(memoryMapper.selectCount(any())).thenReturn(9L);
        assertEquals(0, service.evictBeforeInsert(7L, 42L, 1), "9+1=10 未超配额,不淘汰");

        when(memoryMapper.selectCount(any())).thenReturn(10L);
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(1, 1, 0)));
        assertEquals(1, service.evictBeforeInsert(7L, 42L, 1), "10+1>10,先淘汰 1 条再插入");
        verify(memoryMapper).deleteById(1L);
    }

    @Test
    void scheduledRunDoesNothingWhenDisabled() {
        properties.setEnabled(false);
        service.scheduledRun();
        verifyNoInteractions(governanceMapper, memoryMapper);
    }

    @Test
    void manualRunAlsoRequiresNothingButWorksWhenDisabledSwitch() {
        // 手动触发是显式意图,不受 enabled 限制(e2e 与运维都靠它验证)
        properties.setEnabled(false);
        properties.setArchiveRetentionDays(0);
        properties.setTtlDays(0);
        when(governanceMapper.countOverQuotaGroups(anyInt())).thenReturn(List.of());
        Map<String, Long> result = service.runOnce();
        assertEquals(0L, result.get("quotaEvicted"));
        assertTrue(result.containsKey("archivedMessages"));
    }
}
