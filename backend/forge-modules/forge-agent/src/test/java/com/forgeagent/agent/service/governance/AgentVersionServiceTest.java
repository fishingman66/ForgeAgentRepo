package com.forgeagent.agent.service.governance;

import com.forgeagent.agent.service.support.AgentSkillVersionService;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.forgeagent.agent.domain.AgentSkillInstall;
import com.forgeagent.agent.domain.AgentVersion;
import com.forgeagent.agent.mapper.AgentSkillInstallMapper;
import com.forgeagent.agent.mapper.AgentVersionMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 配置快照的技能绑定(P1-B)。
 *
 * <p>锁四件事:①skillRefs 进快照并参与 hash;②技能内容变化会改变 hash;
 * ③老快照(无 skillRefs)回滚时**跳过而不是清空**;④回滚做覆盖式同步(删多余 + 改 pin)。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentVersionServiceTest {

    @BeforeAll
    static void warmUpLambdaCache() {
        // 纯 Mockito 下 Wrappers.lambdaQuery()/lambdaUpdate() 构造即解析列名,需预热
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, com.forgeagent.agent.domain.AgentSkillInstall.class);
        TableInfoHelper.initTableInfo(assistant, com.forgeagent.agent.domain.AgentVersion.class);
    }


    @Mock private AgentVersionMapper versionMapper;
    @Mock private AgentSkillInstallMapper skillInstallMapper;
    @Mock
    private AgentSkillVersionService skillVersionService;

    private AgentVersionService service() {
        return new AgentVersionService(versionMapper, skillInstallMapper, skillVersionService);
    }

    private static AgentInfo agent() {
        AgentInfo a = new AgentInfo();
        a.setId(7L);
        a.setWorkspaceId(1L);
        a.setName("客服助手");
        a.setSystemPrompt("你是客服");
        a.setVersion(3);
        return a;
    }

    private static List<Map<String, Object>> refs(String hash) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", 10L);
        ref.put("version", 2);
        ref.put("name", "退换货处理");
        ref.put("contentHash", hash);
        return List.of(ref);
    }

    @Test
    void snapshotCarriesSkillRefs() {
        when(skillInstallMapper.skillRefsOf(any())).thenReturn(refs("aaa"));

        Map<String, Object> snapshot = service().buildSnapshot(agent());

        assertTrue(snapshot.containsKey("skillRefs"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> value = (List<Map<String, Object>>) snapshot.get("skillRefs");
        assertEquals(1, value.size());
        assertEquals(10L, value.getFirst().get("id"));
        assertEquals(2, value.getFirst().get("version"));
    }

    @Test
    void skillContentChangeChangesConfigHash() {
        AgentVersionService svc = service();
        when(skillInstallMapper.skillRefsOf(any())).thenReturn(refs("aaa"))
            .thenReturn(refs("bbb"));

        String h1 = svc.hashOf(svc.buildSnapshot(agent()));
        String h2 = svc.hashOf(svc.buildSnapshot(agent()));

        assertNotEquals(h1, h2);
        assertEquals(64, h1.length());
    }

    @Test
    void createSnapshotPinsCurrentVersionsAndArchivesContent() {
        when(versionMapper.nextVersionNo(any())).thenReturn(3);
        when(skillInstallMapper.selectList(any())).thenReturn(List.of(skillInstall(10L)));
        when(skillInstallMapper.skillRefsOf(any())).thenReturn(refs("aaa"));

        int versionNo = service().createSnapshot(agent(), "发布", "publish");

        assertEquals(3, versionNo);
        verify(skillVersionService).archiveBySkillIds(List.of(10L));
        // 发布语义:pin 当前版本
        verify(skillInstallMapper).pinCurrentVersions(any());
    }

    @Test
    void rollbackSnapshotDoesNotRepin() {
        when(versionMapper.nextVersionNo(any())).thenReturn(4);
        when(skillInstallMapper.selectList(any())).thenReturn(List.of(skillInstall(10L)));
        when(skillInstallMapper.skillRefsOf(any())).thenReturn(refs("aaa"));

        service().createSnapshot(agent(), "回滚自 v1", "rollback", false);

        verify(skillInstallMapper, never()).pinCurrentVersions(any());
    }

    @Test
    void legacySnapshotWithoutRefsSkipsRestore() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "旧版");

        service().restoreSkillBinding(7L, snapshot);

        verify(skillInstallMapper, never()).deleteById(any());
        verify(skillInstallMapper, never()).insertPinned(any(), any(), any());
    }

    @Test
    void restoreReplacesBindingSetAndPinnedVersion() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("skillRefs", refs("aaa")); // 目标只有 skill 10,版本 2

        when(skillInstallMapper.currentBindings(any())).thenReturn(List.of(
                row(100L, 10L),
                row(101L, 11L)   // 快照里没有,应被物理删除
            ));

        service().restoreSkillBinding(7L, snapshot);

        verify(skillInstallMapper).deleteById(101L);
        verify(skillInstallMapper).update(isNull(), any());
        verify(skillInstallMapper, never()).insertPinned(any(), any(), any());
    }

    @Test
    void restoreInsertsMissingBinding() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("skillRefs", refs("aaa"));

        when(skillInstallMapper.currentBindings(any())).thenReturn(List.of()); // 当前没有绑定

        service().restoreSkillBinding(7L, snapshot);

        verify(skillInstallMapper).insertPinned(7L, 2, 10L);
    }

    @Test
    void diffRendersSkillRefsAsReadableList() {
        String before = "{\"skillRefs\":[{\"id\":10,\"version\":1,\"name\":\"退换货处理\"}]}";
        String after = "{\"skillRefs\":[{\"id\":10,\"version\":2,\"name\":\"退换货处理\"}]}";
        when(versionMapper.selectOne(any())).thenReturn(versionWithSnapshot(before), versionWithSnapshot(after));

        com.forgeagent.agent.domain.vo.VersionDiffVo result = service().diff(7L, 1, 2);
        com.forgeagent.agent.domain.vo.VersionDiffVo.FieldChange change = result.getChanges().get("skillRefs");

        assertEquals("退换货处理 v1", change.getFrom());
        assertEquals("退换货处理 v2", change.getTo());
    }

    @Test
    @SuppressWarnings("unchecked")
    void d1SnapshotAgentDeepCopiesWithoutPollutingCacheObject() {
        // D1 核心:snapshotAgent 必须返回新对象,不能把灰度配置写进 @Cacheable 共享引用
        AgentInfo cached = agent();
        String snap = "{\"systemPrompt\":\"灰度版提示词\",\"modelId\":999,\"name\":\"灰度助手\"}";
        when(versionMapper.selectOne(any())).thenReturn(versionWithSnapshot(snap));

        AgentInfo view = service().snapshotAgent(cached, 2);

        assertEquals("灰度版提示词", view.getSystemPrompt());
        assertEquals(999L, view.getModelId());
        assertEquals(2, view.getPublishedVersion());
        // 原缓存对象必须原封不动
        assertEquals("你是客服", cached.getSystemPrompt());
        assertEquals(3, cached.getVersion());
        assertNotEquals(view, cached);
    }

    @Test
    void d1SnapshotAgentFallsBackOnMissingVersion() {
        AgentInfo cached = agent();
        when(versionMapper.selectOne(any())).thenReturn(null);
        AgentInfo view = service().snapshotAgent(cached, 99);
        assertEquals(cached, view);
        assertEquals("你是客服", view.getSystemPrompt());
    }

    @Test
    void d1SnapshotAgentSkipsWithoutVersion() {
        AgentInfo cached = agent();
        assertEquals(cached, service().snapshotAgent(cached, null));
    }

    @Test
    void copyAgentDetachesCachedSharedReference() {
        // @Cacheable 返回共享引用：就地改写会污染全局缓存。copyAgent 必须给出独立副本。
        AgentInfo cached = agent();
        AgentInfo copy = service().copyAgent(cached);
        assertNotEquals(System.identityHashCode(cached), System.identityHashCode(copy),
            "必须是不同对象，不能返回原引用");
        copy.setSystemPrompt("草稿提示词");
        copy.setModelId(99L);
        assertEquals("你是客服", cached.getSystemPrompt(), "改副本不得影响缓存原对象");
        assertEquals(null, cached.getModelId(), "改副本不得影响缓存原对象");
        assertEquals("草稿提示词", copy.getSystemPrompt());
        assertEquals(Long.valueOf(99L), copy.getModelId());
        assertNull(service().copyAgent(null), "null 入参应安全返回 null");
    }

    private static AgentVersion versionWithSnapshot(String json) {
        AgentVersion v = new AgentVersion();
        v.setSnapshotJson(json);
        return v;
    }

    private static com.forgeagent.agent.domain.AgentSkillInstall skillInstall(Long skillId) {
        com.forgeagent.agent.domain.AgentSkillInstall i = new com.forgeagent.agent.domain.AgentSkillInstall();
        i.setSkillId(skillId);
        return i;
    }

    private static Map<String, Object> row(Long id, Long skillId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("skill_id", skillId);
        return m;
    }
}
