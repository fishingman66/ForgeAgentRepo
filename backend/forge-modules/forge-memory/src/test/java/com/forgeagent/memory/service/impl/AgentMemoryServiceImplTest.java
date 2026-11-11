package com.forgeagent.memory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.memory.config.MemoryGovernanceProperties;
import com.forgeagent.memory.config.MemoryVectorProperties;
import com.forgeagent.memory.domain.AgentMemory;
import com.forgeagent.memory.domain.vo.AgentMemoryVo;
import com.forgeagent.memory.mapper.AgentMemoryMapper;
import com.forgeagent.memory.mapper.MemoryGovernanceMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆召回回归单测(P3 / M1.5):锁定 D2 候选池排序、D3 前缀稳定、
 * D4 命中反馈封顶、D5 提示词与读侧脱敏。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentMemoryServiceImplTest {

    @Mock
    private AgentMemoryMapper memoryMapper;
    @Mock
    private AgentConfigCacheService agentConfigCache;
    @Mock
    private WorkspacePermissionHelper permissionHelper;
    /** M2':默认 mock 为关闭,这些用例继续锁定纯评分召回行为 */
    @Mock
    private MemoryVectorService memoryVectorService;
    @Mock
    private MemoryVectorProperties vectorProperties;
    /** M3:默认 mock 为关闭(reserveChars 0),既有用例继续锁定「无图谱」行为 */
    @Mock
    private MemoryGraphInjection graphInjection;
    @Mock
    private KgService kgService;
    /** M4:治理三件套,既有用例默认不触达 */
    @Mock
    private MemoryGovernanceService governanceService;
    @Mock
    private MemoryGovernanceMapper governanceMapper;
    @Mock
    private MemoryGovernanceProperties governanceProperties;

    @InjectMocks
    private AgentMemoryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoTestSupport.initAgentMemory();
    }

    private static AgentMemory mem(long id, String factKey, String content, int importance, int hits) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setAgentId(7L);
        m.setUserId(42L);
        m.setFactKey(factKey);
        m.setContent(content);
        m.setImportance(importance);
        m.setHitCount(hits);
        m.setCreateTime(LocalDateTime.now());
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void candidateQueryOrdersByIdDesc() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        service.buildMemoryBlock(7L, 42L);
        ArgumentCaptor<LambdaQueryWrapper<AgentMemory>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(memoryMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("ORDER BY"), "无 ORDER BY 的 limit 会让新记忆永远进不了候选池");
        assertTrue(sql.contains("DESC"));
    }

    @Test
    void outputOrderIsStableByFactKeyNotByScore() {
        // 分数顺序 c > b > a,factKey 顺序 a < b < c,两者刻意相反
        List<AgentMemory> low = List.of(
            mem(1, "c", "CCC", 10, 0),
            mem(2, "a", "AAA", 1, 0),
            mem(3, "b", "BBB", 5, 0));
        List<AgentMemory> changed = List.of(
            mem(1, "c", "CCC", 1, 0),
            mem(2, "a", "AAA", 10, 50),
            mem(3, "b", "BBB", 5, 0));
        when(memoryMapper.selectList(any())).thenReturn(low, changed);
        String first = service.buildMemoryBlock(7L, 42L);
        String second = service.buildMemoryBlock(7L, 42L);

        assertTrue(first.indexOf("AAA") < first.indexOf("BBB"));
        assertTrue(first.indexOf("BBB") < first.indexOf("CCC"));
        assertEquals(first, second, "评分/hitCount 变化不得改变注入顺序,否则 KV Cache 前缀抖动");
    }

    @Test
    void hitCountContributionIsCapped() {
        AgentMemory fresh = mem(1, "a", "A", 5, 0);
        AgentMemory veteran = mem(1, "a", "A", 5, 1_000_000);
        double delta = AgentMemoryServiceImpl.scoreOf(veteran) - AgentMemoryServiceImpl.scoreOf(fresh);
        assertTrue(delta > 0, "命中次数仍应提供有限加成");
        assertTrue(delta <= 2.0 + 1e-9, "加成必须封顶,否则新记忆永远排不进来");
    }

    @Test
    void readSideMasksLegacyPlaintextMemory() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
            mem(1, "phone", "我的手机号是13812345678", 5, 0)));
        String block = service.buildMemoryBlock(7L, 42L);
        assertFalse(block.contains("13812345678"), "历史明文记忆不得进模型上下文");
        assertTrue(block.contains("138****5678"));
    }

    @Test
    void extractPromptForbidsSensitivePlaintext() {
        assertTrue(AgentMemoryServiceImpl.EXTRACT_SYS_PROMPT.contains("手机号"));
        assertTrue(AgentMemoryServiceImpl.EXTRACT_SYS_PROMPT.contains("身份证号"));
        assertTrue(AgentMemoryServiceImpl.EXTRACT_SYS_PROMPT.contains("不得输出"));
    }

    @Test
    void semanticHitCanEnterSelectionBeyondCandidateRank() {
        // 11 条评分候选,注入上限 10 条;语义命中的记忆不在候选池里,
        // 只有融合排序把它顶到前面才会被选中
        when(memoryVectorService.enabled()).thenReturn(true);
        when(vectorProperties.getTopK()).thenReturn(5);
        when(vectorProperties.getSemanticWeight()).thenReturn(0.8);
        List<AgentMemory> candidates = new java.util.ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            candidates.add(mem(i, "k" + i, "候选" + i, 5, 0));
        }
        AgentMemory semantic = mem(99, "k99", "语义命中记忆", 1, 0);
        when(memoryMapper.selectList(any())).thenReturn(candidates);
        when(memoryVectorService.search(any(), any(), anyString(), anyInt()))
            .thenReturn(java.util.List.of(new MemoryVectorService.Hit(semantic, 0.95)));

        String block = service.buildMemoryBlock(7L, 42L, "语义查询");

        assertTrue(block.contains("语义命中记忆"), "向量命中应能补进候选池并入选");
        verify(memoryVectorService).ensurePendingAsync(7L, 42L);
    }

    @Test
    void graphBlockIsAppendedAfterStableFacts() {
        when(graphInjection.reserveChars()).thenReturn(300);
        when(graphInjection.build(any(), anyList(), anyInt()))
            .thenReturn("[关联背景]\n用户 —负责→ ForgeAgent 项目");
        when(memoryMapper.selectList(any())).thenReturn(List.of(
            mem(1, "a", "AAA", 5, 0),
            mem(2, "b", "BBB", 5, 0)));

        String block = service.buildMemoryBlock(7L, 42L, "那个项目用什么数据库");

        assertTrue(block.contains("AAA") && block.contains("BBB"));
        assertTrue(block.contains("[关联背景]"));
        assertTrue(block.indexOf("[关联背景]") > block.indexOf("BBB"),
            "动态关联背景必须追加在稳定事实之后,否则跨轮前缀位移会打掉 KV Cache");
    }

    @Test
    void graphBudgetSqueezesMemorySelection() {
        // 记忆块总预算固定 1500 字符,图增强预留 1200 后只剩 300,三条 400 字的记忆只能进来 0 条
        when(graphInjection.reserveChars()).thenReturn(1200);
        String longContent = "内容".repeat(200);
        when(memoryMapper.selectList(any())).thenReturn(List.of(
            mem(1, "a", longContent, 5, 0),
            mem(2, "b", longContent, 5, 0)));

        assertEquals("", service.buildMemoryBlock(7L, 42L, "任意问题"),
            "图预算挤占后放不下任何事实时应整块不注入,而不是超预算硬塞");
    }

    @Test
    void extractionParsesFactsAndTriplesFromObjectFormat() {
        String json = "{\"facts\":[{\"key\":\"db\",\"content\":\"项目使用 MySQL\",\"importance\":7}],"
            + "\"triples\":[{\"subject\":\"ForgeAgent 项目\",\"relation\":\"使用\","
            + "\"object\":\"MySQL\",\"fact\":\"db\"}]}";

        AgentMemoryServiceImpl.Extraction extraction =
            service.parseExtraction("```json\n" + json + "\n```");

        assertEquals(1, extraction.facts().size());
        assertEquals("db", extraction.facts().getFirst().key());
        assertEquals(1, extraction.triples().size());
        assertEquals("db", extraction.triples().getFirst().factKey(),
            "三元组的 fact 字段要能回指事实键,否则落库时找不到证据记忆");
    }

    @Test
    void extractionStillAcceptsLegacyFactArray() {
        AgentMemoryServiceImpl.Extraction extraction = service.parseExtraction(
            "[{\"key\":\"a\",\"content\":\"AAA\",\"importance\":5}]");
        assertEquals(1, extraction.facts().size());
        assertTrue(extraction.triples().isEmpty(), "旧版数组输出不得解析失败");
    }

    @Test
    void extractionCapsTripleCount() {
        StringBuilder json = new StringBuilder("{\"facts\":[],\"triples\":[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"subject\":\"S").append(i).append("\",\"relation\":\"使用\",\"object\":\"O")
                .append(i).append("\"}");
        }
        json.append("]}");
        assertEquals(6, service.parseExtraction(json.toString()).triples().size(),
            "单轮三元组条数要封顶,避免模型输出把图库写爆");
    }

    @Test
    void extractPromptAsksForTriples() {
        assertTrue(AgentMemoryServiceImpl.EXTRACT_SYS_PROMPT.contains("triples"));
        assertTrue(AgentMemoryServiceImpl.EXTRACT_SYS_PROMPT.contains("subject"));
        assertTrue(AgentMemoryServiceImpl.TRIPLE_SYS_PROMPT.contains("triples"));
    }

    @Test
    void rebuildRejectsWhenGraphDisabled() {
        AgentInfo agent = new AgentInfo();
        agent.setWorkspaceId(1L);
        when(agentConfigCache.getAgentById(7L)).thenReturn(agent);
        when(graphInjection.enabled()).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.rebuildGraphAsync(7L, 42L));
        assertTrue(ex.getMessage().contains("知识图谱未启用"),
            "图谱关闭时不能返回假成功,否则运维会以为补建跑过了");
    }

    @Test
    @SuppressWarnings("unchecked")
    void candidateQueryFiltersExpiredMemories() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        service.buildMemoryBlock(7L, 42L);
        ArgumentCaptor<LambdaQueryWrapper<AgentMemory>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(memoryMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        assertTrue(sql.contains("expire_at"), "显式 TTL 过期的记忆不得再进注入");
    }

    private static AgentInfo agentOf(long ws) {
        AgentInfo agent = new AgentInfo();
        agent.setWorkspaceId(ws);
        return agent;
    }

    @Test
    void forgetCascadesAcrossLayers() {
        when(agentConfigCache.getAgentById(7L)).thenReturn(agentOf(1L));
        when(memoryMapper.countByUser(7L, 42L)).thenReturn(3L);

        long deleted = service.forgetUser(7L, 42L, "FORGET");

        assertEquals(3L, deleted);
        verify(memoryMapper).physicalDeleteByUser(7L, 42L);
        verify(kgService).deleteScope(KgScope.agent(7L, 42L));
        verify(governanceMapper).deleteMemoryArchiveByUser(42L);
        verify(governanceMapper).deleteMessageArchiveByUser(42L);
    }

    @Test
    void forgetRequiresExplicitConfirmAndUser() {
        when(agentConfigCache.getAgentById(7L)).thenReturn(agentOf(1L));
        ServiceException noConfirm = assertThrows(ServiceException.class,
            () -> service.forgetUser(7L, 42L, "yes"));
        assertTrue(noConfirm.getMessage().contains("FORGET"), "二次确认口令必须精确匹配");

        ServiceException noUser = assertThrows(ServiceException.class,
            () -> service.forgetUser(7L, null, "FORGET"));
        assertTrue(noUser.getMessage().contains("平台级"), "平台级记忆不属于任何用户,不可遗忘");
        verify(memoryMapper, never()).physicalDeleteByUser(anyLong(), anyLong());
    }

    @Test
    void searchMemoriesFallsBackToScoreAndMasks() {
        when(agentConfigCache.getAgentById(7L)).thenReturn(agentOf(1L));
        when(memoryVectorService.enabled()).thenReturn(false);
        when(memoryMapper.selectList(any())).thenReturn(List.of(
            mem(1, "a", "普通记忆甲", 1, 0),
            mem(2, "b", "我的手机号是13812345678", 9, 0),
            mem(3, "c", "普通记忆乙", 5, 0)));

        List<AgentMemoryVo> rows = service.searchMemories(7L, 42L, "任意词", 2);

        assertEquals(2, rows.size());
        assertEquals("b", rows.get(0).getFactKey(), "回退走评分召回,高分在前");
        assertFalse(rows.get(0).getContent().contains("13812345678"), "读侧脱敏兜底同样适用于检索结果");
    }

    @Test
    void searchMemoriesUsesVectorWhenEnabled() {
        when(agentConfigCache.getAgentById(7L)).thenReturn(agentOf(1L));
        when(memoryVectorService.enabled()).thenReturn(true);
        when(memoryVectorService.search(any(), any(), anyString(), anyInt()))
            .thenReturn(List.of(new MemoryVectorService.Hit(mem(99, "k99", "语义命中", 1, 0), 0.9)));

        List<AgentMemoryVo> rows = service.searchMemories(7L, 42L, "语义查询", 5);

        assertEquals(1, rows.size());
        assertEquals("k99", rows.getFirst().getFactKey());
    }

    @Test
    void runGovernanceRequiresEnabledSwitch() {
        when(governanceProperties.isEnabled()).thenReturn(false);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.runGovernance());
        assertTrue(ex.getMessage().contains("治理未启用"));

        when(governanceProperties.isEnabled()).thenReturn(true);
        when(governanceService.runOnce()).thenReturn(java.util.Map.of("ttlArchived", 2L));
        assertEquals(2L, service.runGovernance().get("ttlArchived"));
    }
}
