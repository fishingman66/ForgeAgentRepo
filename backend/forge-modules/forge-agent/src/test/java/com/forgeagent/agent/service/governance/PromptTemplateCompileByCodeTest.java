package com.forgeagent.agent.service.governance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.agent.domain.AgentPromptTemplate;
import com.forgeagent.agent.domain.AgentPromptVersion;
import com.forgeagent.agent.mapper.AgentPromptTemplateMapper;
import com.forgeagent.agent.mapper.AgentPromptVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * B4:compileByCode(按模板名取已发布模板文本)单测。锁四件事:
 * ① 工作空间自有模板优先于平台共享(workspace_id=0);
 * ② 设了稳定版取稳定版本内容,否则取模板 content;
 * ③ 未配置/空白名返回 null(调用方回退内置兜底),不抛异常;
 * ④ 查询条件下推 name/workspace/status。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PromptTemplateCompileByCodeTest {

    @Mock private AgentPromptTemplateMapper templateMapper;
    @Mock private AgentPromptVersionMapper versionMapper;

    private PromptTemplateService service;

    @BeforeAll
    static void warmUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentPromptTemplate.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentPromptVersion.class);
    }

    @BeforeEach
    void setUp() {
        service = new PromptTemplateService(templateMapper, versionMapper);
    }

    private static AgentPromptTemplate template(Long id, Long ws, String name, String status,
                                                Long stableVersionId, String content) {
        AgentPromptTemplate t = new AgentPromptTemplate();
        t.setId(id);
        t.setWorkspaceId(ws);
        t.setName(name);
        t.setStatus(status);
        t.setStableVersionId(stableVersionId);
        t.setContent(content);
        return t;
    }

    @Test
    void workspaceTemplatePreferredOverShared() {
        when(templateMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            template(2L, 7L, "RCA_STANDARD_PROPOSE", "published", null, "工作空间自有提示词"),
            template(1L, 0L, "RCA_STANDARD_PROPOSE", "published", null, "共享提示词")));

        // mapper 返回顺序由 orderByDesc(workspaceId) 决定,首选行必须取工作空间自有
        assertEquals("工作空间自有提示词", service.compileByCode(7L, "RCA_STANDARD_PROPOSE"));
    }

    @Test
    void stableVersionContentPreferredOverDraftContent() {
        when(templateMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            template(1L, 0L, "RCA_STANDARD_REPORT", "published", 99L, "模板表草稿内容")));
        AgentPromptVersion version = new AgentPromptVersion();
        version.setId(99L);
        version.setContent("稳定版内容");
        when(versionMapper.selectById(99L)).thenReturn(version);

        String result = service.compileByCode(7L, "RCA_STANDARD_REPORT");

        assertEquals("稳定版内容", result);
    }

    @Test
    void fallsBackToTemplateContentWhenNoStableVersion() {
        when(templateMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            template(1L, 0L, "RCA_STANDARD_PROPOSE", "published", null, "模板当前内容")));

        assertEquals("模板当前内容", service.compileByCode(7L, "RCA_STANDARD_PROPOSE"));
        org.mockito.Mockito.verifyNoInteractions(versionMapper);
    }

    @Test
    void missingOrBlankNameReturnsNull() {
        when(templateMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertNull(service.compileByCode(7L, "NOT_CONFIGURED"));
        assertNull(service.compileByCode(7L, "  "));
        org.mockito.Mockito.verifyNoInteractions(versionMapper);
    }

    @Test
    void queryFiltersNameWorkspaceAndPublishedStatus() {
        when(templateMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.compileByCode(7L, "RCA_STANDARD_PROPOSE");

        ArgumentCaptor<LambdaQueryWrapper<AgentPromptTemplate>> cap =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(templateMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("name"), sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("workspace_id"), sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("status"), sql);
    }
}
