package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.domain.AgentSkillInstall;
import com.forgeagent.agent.domain.vo.AgentSkillBindVo;
import com.forgeagent.agent.domain.vo.SkillInstallTargetVo;
import com.forgeagent.agent.mapper.AgentSkillInstallMapper;
import com.forgeagent.agent.mapper.AgentSkillMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agent.service.support.AgentSkillVersionService;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.exception.ServiceException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能安装/卸载链路单测(技能管理页「安装管理」与 Agent 侧技能抽屉的数据来源)。
 *
 * <p>锁三件事:①安装目标清单带安装态且包含未安装应用;②应用侧清单包含已停用技能
 * (运营要能看到并卸载)、跳过孤儿绑定;③解绑走物理删除——逻辑删除会留下
 * {@code del_flag} 非 0 的记录占住 {@code uk_skill_agent},卸载后再安装直接 Duplicate entry。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentSkillServiceImplTest {

    private static final LocalDateTime INSTALL_AT = LocalDateTime.of(2026, 11, 19, 10, 0);

    @Mock
    private AgentSkillMapper skillMapper;
    @Mock
    private AgentSkillInstallMapper installMapper;
    @Mock
    private AgentInfoMapper agentMapper;
    @Mock
    private ResourceAccessGuard resourceGuard;
    @Mock
    private AgentSkillVersionService skillVersionService;

    private AgentSkillServiceImpl service;

    @BeforeAll
    static void warmUpLambdaCache() {
        // 纯 Mockito 无 Spring 上下文时 MP 的 lambda 列名解析需要预热
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentSkill.class);
        TableInfoHelper.initTableInfo(assistant, AgentSkillInstall.class);
        TableInfoHelper.initTableInfo(assistant, AgentInfo.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentSkillServiceImpl(skillMapper, installMapper, agentMapper, resourceGuard,
            skillVersionService);
    }

    private static AgentSkill skill(Long id, String status) {
        AgentSkill s = new AgentSkill();
        s.setId(id);
        s.setName("退换货处理");
        s.setDescription("引导完成退换货申请");
        s.setVersion(3);
        s.setStatus(status);
        return s;
    }

    private static AgentSkillInstall install(Long skillId, Long agentId) {
        AgentSkillInstall i = new AgentSkillInstall();
        i.setSkillId(skillId);
        i.setAgentId(agentId);
        i.setCreateTime(INSTALL_AT);
        return i;
    }

    private static AgentInfo agent(Long id, String name) {
        AgentInfo a = new AgentInfo();
        a.setId(id);
        a.setName(name);
        a.setStatus("1");
        return a;
    }

    @Test
    void installTargetsMarkInstalledAgentAndKeepOthers() {
        when(skillMapper.selectById(1L)).thenReturn(skill(1L, "0"));
        when(installMapper.selectList(any())).thenReturn(List.of(install(1L, 2L)));
        when(agentMapper.selectList(any())).thenReturn(List.of(agent(2L, "客服助手"), agent(3L, "销售助手")));

        List<SkillInstallTargetVo> rows = service.listInstallTargets(1L);

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).getInstalled());
        assertEquals("客服助手", rows.get(0).getAgentName());
        assertEquals(INSTALL_AT.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            rows.get(0).getInstallTime().getTime());
        assertFalse(rows.get(1).getInstalled());
        assertNull(rows.get(1).getInstallTime());
    }

    @Test
    void installTargetsRejectsMissingSkillBeforeAnyQuery() {
        when(skillMapper.selectById(9L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.listInstallTargets(9L));

        assertTrue(ex.getMessage().contains("技能不存在"));
        verify(installMapper, never()).selectList(any());
    }

    @Test
    void installedSkillsIncludeDisabledForOperator() {
        when(installMapper.selectList(any())).thenReturn(List.of(install(10L, 5L)));
        when(skillMapper.selectList(any())).thenReturn(List.of(skill(10L, "1")));

        List<AgentSkillBindVo> rows = service.listInstalledSkills(5L);

        assertEquals(1, rows.size());
        assertEquals("1", rows.get(0).getStatus());
        assertEquals(10L, rows.get(0).getSkillId().longValue());
        assertEquals(3, rows.get(0).getVersion().intValue());
    }

    @Test
    void orphanBindingIsSkipped() {
        when(installMapper.selectList(any())).thenReturn(List.of(install(10L, 5L)));
        when(skillMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.listInstalledSkills(5L).isEmpty());
    }

    @Test
    void noBindingReturnsEmptyWithoutQueryingSkills() {
        when(installMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.listInstalledSkills(5L).isEmpty());
        verify(skillMapper, never()).selectList(any());
    }

    @Test
    void reinstallPurgesLogicalDeletedResidueBeforeInsert() {
        when(skillMapper.selectById(1L)).thenReturn(skill(1L, "0"));
        when(installMapper.selectCount(any())).thenReturn(0L);
        when(installMapper.insert(any(AgentSkillInstall.class))).thenReturn(1);

        assertTrue(service.installToAgent(1L, 2L));

        verify(installMapper).physicalUnbind(1L, 2L);
        verify(installMapper).insert(any(AgentSkillInstall.class));
    }

    @Test
    void installedSkillShortCircuitsWithoutResidueCleanup() {
        when(skillMapper.selectById(1L)).thenReturn(skill(1L, "0"));
        when(installMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.installToAgent(1L, 2L));

        verify(installMapper, never()).physicalUnbind(1L, 2L);
        verify(installMapper, never()).insert(any(AgentSkillInstall.class));
    }

    @Test
    void disabledSkillCannotBeInstalled() {
        when(skillMapper.selectById(1L)).thenReturn(skill(1L, "1"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.installToAgent(1L, 2L));

        assertTrue(ex.getMessage().contains("已停用"));
    }

    @Test
    void uninstallUsesPhysicalDelete() {
        when(installMapper.physicalUnbind(1L, 2L)).thenReturn(1);
        assertTrue(service.uninstallFromAgent(1L, 2L));

        when(installMapper.physicalUnbind(1L, 3L)).thenReturn(0);
        assertFalse(service.uninstallFromAgent(1L, 3L));
    }

    @Test
    void deletingSkillCascadesPhysicalUnbind() {
        when(skillMapper.selectById(1L)).thenReturn(skill(1L, "0"));
        when(skillMapper.deleteByIds(List.of(1L))).thenReturn(1);

        assertTrue(service.deleteByIds(List.of(1L)));

        verify(installMapper).physicalUnbindBySkillIds(List.of(1L));
        verify(installMapper, never()).delete(org.mockito.ArgumentMatchers.<Wrapper<AgentSkillInstall>>any());
    }

    @Test
    void installedSkillIsResolvedByPinnedVersion() {
        AgentSkillInstall install = install(10L, 5L);
        install.setPinnedVersion(1);
        when(installMapper.selectList(any())).thenReturn(List.of(install));
        AgentSkill current = skill(10L, "0");
        current.setVersion(3); // 技能已升到 v3,但该 Agent 钉在 v1
        when(skillMapper.selectList(any())).thenReturn(List.of(current));
        AgentSkill pinView = skill(10L, "0");
        pinView.setVersion(1);
        when(skillVersionService.applyPinned(current, 1)).thenReturn(pinView);

        List<AgentSkill> list = service.listInstalledByAgent(5L);

        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getVersion().intValue());
        verify(skillVersionService).applyPinned(current, 1);
    }

    @Test
    void installingPinsCurrentSkillVersion() {
        AgentSkill skill = skill(1L, "0");
        skill.setVersion(4);
        when(skillMapper.selectById(1L)).thenReturn(skill);
        when(installMapper.selectCount(any())).thenReturn(0L);
        when(installMapper.insert(any(AgentSkillInstall.class))).thenReturn(1);

        assertTrue(service.installToAgent(1L, 2L));

        org.mockito.ArgumentCaptor<AgentSkillInstall> captor =
            org.mockito.ArgumentCaptor.forClass(AgentSkillInstall.class);
        verify(installMapper).insert(captor.capture());
        assertEquals(4, captor.getValue().getPinnedVersion().intValue());
    }
}
