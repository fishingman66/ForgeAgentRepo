package com.forgeagent.agent.service.governance;

import com.forgeagent.agent.service.support.AgentSkillVersionService;
import com.forgeagent.agentcore.domain.AgentInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private AgentSkillVersionService skillVersionService;

    private AgentVersionService service() {
        return new AgentVersionService(jdbc, skillVersionService);
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
        when(jdbc.queryForList(contains("FROM agent_skill_install i"), any(Object[].class)))
            .thenReturn(refs("aaa"));

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
        when(jdbc.queryForList(contains("FROM agent_skill_install i"), any(Object[].class)))
            .thenReturn(refs("aaa"))
            .thenReturn(refs("bbb"));

        String h1 = svc.hashOf(svc.buildSnapshot(agent()));
        String h2 = svc.hashOf(svc.buildSnapshot(agent()));

        assertNotEquals(h1, h2);
        assertEquals(64, h1.length());
    }

    @Test
    void createSnapshotPinsCurrentVersionsAndArchivesContent() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(3);
        when(jdbc.queryForList(contains("SELECT skill_id FROM agent_skill_install"), eq(Long.class),
            any(Object[].class))).thenReturn(List.of(10L));
        when(jdbc.queryForList(contains("FROM agent_skill_install i"), any(Object[].class)))
            .thenReturn(refs("aaa"));

        int versionNo = service().createSnapshot(agent(), "发布", "publish");

        assertEquals(3, versionNo);
        verify(skillVersionService).archiveBySkillIds(List.of(10L));
        // 发布语义:pin 当前版本
        verify(jdbc).update(contains("SET i.pinned_version"), any(Object[].class));
    }

    @Test
    void rollbackSnapshotDoesNotRepin() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(4);
        when(jdbc.queryForList(contains("SELECT skill_id FROM agent_skill_install"), eq(Long.class),
            any(Object[].class))).thenReturn(List.of(10L));
        when(jdbc.queryForList(contains("FROM agent_skill_install i"), any(Object[].class)))
            .thenReturn(refs("aaa"));

        service().createSnapshot(agent(), "回滚自 v1", "rollback", false);

        verify(jdbc, never()).update(contains("SET i.pinned_version"), any(Object[].class));
    }

    @Test
    void legacySnapshotWithoutRefsSkipsRestore() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "旧版");

        service().restoreSkillBinding(7L, snapshot);

        verify(jdbc, never()).update(contains("DELETE FROM agent_skill_install"), any(Object[].class));
        verify(jdbc, never()).update(contains("INSERT INTO agent_skill_install"), any(Object[].class));
    }

    @Test
    void restoreReplacesBindingSetAndPinnedVersion() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("skillRefs", refs("aaa")); // 目标只有 skill 10,版本 2

        when(jdbc.queryForList(contains("SELECT id,skill_id FROM agent_skill_install"), any(Object[].class)))
            .thenReturn(List.of(
                row(100L, 10L),
                row(101L, 11L)   // 快照里没有,应被物理删除
            ));

        service().restoreSkillBinding(7L, snapshot);

        verify(jdbc).update(contains("DELETE FROM agent_skill_install"), eq(101L));
        verify(jdbc).update(contains("UPDATE agent_skill_install SET pinned_version"),
            eq(2), eq(7L), eq(10L));
        verify(jdbc, never()).update(contains("INSERT INTO agent_skill_install"),
            any(Object[].class));
    }

    @Test
    void restoreInsertsMissingBinding() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("skillRefs", refs("aaa"));

        when(jdbc.queryForList(contains("SELECT id,skill_id FROM agent_skill_install"), any(Object[].class)))
            .thenReturn(List.of()); // 当前没有绑定

        service().restoreSkillBinding(7L, snapshot);

        verify(jdbc).update(contains("INSERT INTO agent_skill_install"), eq(7L), eq(2), eq(10L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diffRendersSkillRefsAsReadableList() {
        String before = "{\"skillRefs\":[{\"id\":10,\"version\":1,\"name\":\"退换货处理\"}]}";
        String after = "{\"skillRefs\":[{\"id\":10,\"version\":2,\"name\":\"退换货处理\"}]}";
        when(jdbc.queryForList(contains("SELECT snapshot_json"), eq(String.class), any(Object[].class)))
            .thenReturn(List.of(before), List.of(after));

        Map<String, Object> result = service().diff(7L, 1, 2);
        Map<String, Object> changes = (Map<String, Object>) result.get("changes");
        Map<String, Object> change = (Map<String, Object>) changes.get("skillRefs");

        assertEquals("退换货处理 v1", change.get("from"));
        assertEquals("退换货处理 v2", change.get("to"));
    }

    private static Map<String, Object> row(Long id, Long skillId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("skill_id", skillId);
        return m;
    }
}
