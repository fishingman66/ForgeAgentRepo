package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentSkill;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.forgeagent.agent.mapper.AgentSkillMapper;
import com.forgeagent.agent.mapper.AgentSkillVersionMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能内容版本服务单测(P1-B)。
 *
 * <p>锁三件事:①contentHash 稳定且对任一内容字段敏感(否则 config_hash 检测不到技能改动);
 * ②归档写库带 hash;③按 pin 取内容时不改库、缺版本行时降级。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentSkillVersionServiceTest {

    @Mock private AgentSkillVersionMapper versionMapper;
    @Mock private AgentSkillMapper skillMapper;

    private AgentSkillVersionService service() {
        return new AgentSkillVersionService(versionMapper, skillMapper);
    }

    private static com.forgeagent.agent.domain.AgentSkillVersion versionRow(String name, String instructions, String fragment) {
        com.forgeagent.agent.domain.AgentSkillVersion v = new com.forgeagent.agent.domain.AgentSkillVersion();
        v.setName(name);
        v.setDescription("旧摘要");
        v.setInstructions(instructions);
        v.setToolIds("11");
        v.setMcpIds("21");
        v.setKnowledgeIds("31");
        v.setPromptFragment(fragment);
        v.setContentHash("abc");
        return v;
    }

    private static AgentSkill skill() {
        AgentSkill s = new AgentSkill();
        s.setId(1L);
        s.setName("退换货处理");
        s.setDescription("引导完成退换货申请");
        s.setInstructions("1. 确认订单号\n2. 校验资格");
        s.setToolIds("11,12");
        s.setMcpIds("21");
        s.setKnowledgeIds("31");
        s.setPromptFragment("语气克制");
        s.setVersion(2);
        s.setStatus("0");
        return s;
    }

    @Test
    void contentHashIsStable() {
        AgentSkillVersionService svc = service();
        String first = svc.contentHash(skill());
        String second = svc.contentHash(skill());

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNull(svc.contentHash(null));
    }

    @Test
    void contentHashSensitiveToEveryContentField() {
        AgentSkillVersionService svc = service();
        String base = svc.contentHash(skill());

        AgentSkill renamed = skill();
        renamed.setName("退换货处理V2");
        AgentSkill reDesc = skill();
        reDesc.setDescription("另一段摘要");
        AgentSkill reManual = skill();
        reManual.setInstructions("换了手册");
        AgentSkill reTools = skill();
        reTools.setToolIds("99");
        AgentSkill reFragment = skill();
        reFragment.setPromptFragment("换语气");

        for (AgentSkill changed : List.of(renamed, reDesc, reManual, reTools, reFragment)) {
            assertNotEquals(base, svc.contentHash(changed));
        }
        // 版本号与状态不属于内容,不参与摘要
        AgentSkill reVersion = skill();
        reVersion.setVersion(9);
        reVersion.setStatus("1");
        assertEquals(base, svc.contentHash(reVersion));
    }

    @Test
    void fieldBoundariesDoNotCollide() {
        AgentSkillVersionService svc = service();
        // ("ab","c") 与 ("a","bc") 若拼接无分隔符会撞 hash
        AgentSkill left = skill();
        left.setToolIds("ab");
        left.setMcpIds("c");
        AgentSkill right = skill();
        right.setToolIds("a");
        right.setMcpIds("bc");

        assertNotEquals(svc.contentHash(left), svc.contentHash(right));
    }

    @Test
    void archiveWritesVersionRowWithHash() {
        AgentSkill target = skill();
        service().archive(target);

        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(versionMapper).archive(eq(1L), eq(2), eq("退换货处理"),
            any(), any(), any(), any(), any(), any(), hash.capture(), any());
        assertEquals(64, hash.getValue().length());
    }

    @Test
    void applyPinnedReturnsSameInstanceWhenVersionMatches() {
        AgentSkill target = skill(); // version=2
        assertSame(target, service().applyPinned(target, 2));
        assertSame(target, service().applyPinned(target, null));
    }

    @Test
    void applyPinnedOverridesContentWithoutTouchingEntity() {
        AgentSkill target = skill(); // 当前 v2
        when(versionMapper.selectOne(any())).thenReturn(versionRow("退换货处理(旧版)", "旧手册", "旧语气"));

        AgentSkill view = service().applyPinned(target, 1);

        assertEquals(1L, view.getId().longValue());
        assertEquals(1, view.getVersion().intValue());
        assertEquals("退换货处理(旧版)", view.getName());
        assertEquals("旧手册", view.getInstructions());
        assertEquals("旧语气", view.getPromptFragment());
        // 原实体不被修改
        assertEquals(2, target.getVersion().intValue());
        assertEquals("退换货处理", target.getName());
    }

    @Test
    void applyPinnedFallsBackWhenVersionRowMissing() {
        AgentSkill target = skill();
        when(versionMapper.selectOne(any())).thenReturn(null);

        AgentSkill view = service().applyPinned(target, 1);

        assertSame(target, view);
        assertFalse(view.getInstructions().isEmpty());
    }

    @Test
    void contentHashOfMissingVersionIsNull() {
        when(versionMapper.selectOne(any())).thenReturn(null);
        assertNull(service().contentHashOf(1L, 5));
        assertTrue(service().versionOf(1L, 5).isEmpty());
    }
}
