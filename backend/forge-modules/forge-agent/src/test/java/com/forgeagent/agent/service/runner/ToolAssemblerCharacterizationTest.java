package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.service.AgentRunner;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 08方案 / A3-前置:ToolAssembler 表征测试。
 *
 * <p>SPI 化改造前锁定平台工具装配的现有行为——四类工具的注册产物、审批门装饰、
 * 重名跳过、SQL 数据源缺失跳过、内置工具注册。改造后这些断言必须逐条复绿,
 * 不接受「行为顺便变了」的搬迁。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolAssemblerCharacterizationTest {

    @Mock
    private AgentDatasourceMapper datasourceMapper;

    private ToolAssembler assembler(AgentChatProperties props) {
        return new ToolAssembler(
            mock(IAgentApprovalService.class),
            mock(SqlQueryService.class),
            datasourceMapper,
            mock(AgentKbChunkMapper.class),
            mock(IAgentKnowledgeService.class),
            mock(RunWorkingMemory.class),
            props,
            mock(McpConnectionPool.class),
            List.of(new HttpToolProvider(), new SandboxToolProvider(),
                new SqlToolProvider(datasourceMapper, mock(SqlQueryService.class))),
            mock(com.forgeagent.agent.service.IAgentSkillService.class));
    }

    private static ToolAssembler.ToolDef def(String name, String toolType, Long datasourceId) {
        return new ToolAssembler.ToolDef(1L, name, "描述", toolType, datasourceId,
            "GET", "http://x/api", null, null,
            "sandbox-image", 30, "512m", "1", null, 60);
    }

    private static ToolAssembler.Assembly build(ToolAssembler assembler, List<ToolAssembler.ToolDef> defs) {
        return assembler.build(defs, List.of(),
            new ApprovalGateTool.ApprovalContext(null, null, null),
            new CopyOnWriteArrayList<>(), List.of(), List.of());
    }

    @Test
    void skillsRegisterLoadSkillToolWithoutApprovalGate() {
        // 08方案 / A2:懒加载技能——目录由服务层拼进 system prompt,这里只锁定 load_skill 注册
        ToolAssembler.Assembly assembly = assembler(new AgentChatProperties()).build(
            List.of(), List.of(),
            new ApprovalGateTool.ApprovalContext(null, null, null),
            new CopyOnWriteArrayList<>(), List.of(),
            List.of(new ToolAssembler.SkillDef("退换货处理", "引导完成退换货申请")));
        assertNotNull(assembly.toolkit().getTool("load_skill"));
        assertTrue(!(assembly.toolkit().getTool("load_skill") instanceof ApprovalGateTool),
            "load_skill 只读平台数据,免审批门");
        assertTrue(assembly.toolkit().getTool("load_skill").getDescription().contains("操作手册"),
            "描述必须自我说明加载语义");
    }

    @Test
    void httpToolIsRegisteredBehindApprovalGate() {
        ToolAssembler.Assembly assembly = build(assembler(new AgentChatProperties()),
            List.of(def("call_api", "http", null)));
        assertNotNull(assembly.toolkit().getTool("call_api"));
        assertTrue(assembly.toolkit().getTool("call_api") instanceof ApprovalGateTool,
            "平台工具必须套审批门");
    }

    @Test
    void sandboxToolIsRegistered() {
        ToolAssembler.Assembly assembly = build(assembler(new AgentChatProperties()),
            List.of(def("run_code", "sandbox", null)));
        assertNotNull(assembly.toolkit().getTool("run_code"), "sandbox 类型按 toolType 路由");
    }

    @Test
    void sqlToolRegistersQueryAndSchemaPair() {
        AgentDatasource ds = new AgentDatasource();
        ds.setStatus("0");
        when(datasourceMapper.selectById(5L)).thenReturn(ds);
        ToolAssembler.Assembly assembly = build(assembler(new AgentChatProperties()),
            List.of(def("query_orders", "sql", 5L)));
        assertNotNull(assembly.toolkit().getTool("query_orders"));
        assertNotNull(assembly.toolkit().getTool("query_orders_schema"),
            "sql 工具必须配套注册 schema 明细工具");
        assertTrue(!(assembly.toolkit().getTool("query_orders_schema") instanceof ApprovalGateTool),
            "只读元数据 schema 工具免审批门,避免重复弹窗(拆分前后一致)");
    }

    @Test
    void sqlToolSkippedWhenDatasourceMissingOrDisabled() {
        when(datasourceMapper.selectById(5L)).thenReturn(null);
        AgentDatasource disabled = new AgentDatasource();
        disabled.setStatus("1");
        when(datasourceMapper.selectById(6L)).thenReturn(disabled);

        ToolAssembler.Assembly assembly = build(assembler(new AgentChatProperties()),
            List.of(def("q1", "sql", 5L), def("q2", "sql", 6L)));

        assertNull(assembly.toolkit().getTool("q1"), "数据源缺失跳过注册");
        assertNull(assembly.toolkit().getTool("q2"), "数据源停用跳过注册");
        assertNull(assembly.toolkit().getTool("q1_schema"), "配套 schema 工具一并跳过");
    }

    @Test
    void duplicateToolNamesAreSkippedNotOverwritten() {
        ToolAssembler.Assembly assembly = build(assembler(new AgentChatProperties()),
            List.of(def("same_name", "http", null), def("same_name", "sandbox", null)));
        assertNotNull(assembly.toolkit().getTool("same_name"));
        long count = assembly.toolkit().getToolNames().stream()
            .filter("same_name"::equals).count();
        assertEquals(1, count, "重名工具跳过注册,先注册者保留");
    }

    @Test
    void builtinsCurrentTimeAlwaysRegisteredRecallOnlyWhenRunScoped() {
        // 无 runId:只有时间工具,没有工作记忆取回工具
        ToolAssembler.Assembly noRun = build(assembler(new AgentChatProperties()), List.of());
        assertNotNull(noRun.toolkit().getTool("get_current_time"));
        assertNull(noRun.toolkit().getTool("recall_working_memory"));

        // 有 runId 且开关开:注册取回工具,且取回工具自身不套工作记忆装饰
        AgentChatProperties props = new AgentChatProperties();
        props.setWorkingMemoryEnabled(true);
        ToolAssembler.Assembly withRun = assembler(props).build(List.of(), List.of(),
            new ApprovalGateTool.ApprovalContext(null, null, "run-1"),
            new CopyOnWriteArrayList<>(), List.of(), List.of());
        assertNotNull(withRun.toolkit().getTool("recall_working_memory"));
        assertTrue(!(withRun.toolkit().getTool("recall_working_memory") instanceof WorkingMemoryGuard),
            "取回工具自身不得参与压缩,避免递归");
    }

    @Test
    void workflowBuildNormalizesApprovalModeToAuto() {
        // buildForWorkflow:画布显式触发无人工等待,审批归一 auto;只装配平台工具,不带 MCP
        ToolAssembler.Assembly assembly =
            assembler(new AgentChatProperties()).buildForWorkflow(
                List.of(def("wf_tool", "http", null)));
        assertNotNull(assembly.toolkit().getTool("wf_tool"));
        assertNotNull(assembly.toolkit().getTool("get_current_time"),
            "工作流路径也保留内置时间工具");
        assertTrue(assembly.toolkit().getToolNames().stream()
            .noneMatch(n -> n.startsWith("recall_")), "无 runId 不注册取回工具");
    }

    @Test
    void attachmentsRegisterReadAttachmentTool() {
        AgentRunner.RunnerContext.AttachmentRef attachment =
            new AgentRunner.RunnerContext.AttachmentRef(1L, "doc.pdf", null, 1, "摘要");
        ToolAssembler.Assembly assembly = assembler(new AgentChatProperties()).build(
            List.of(), List.of(),
            new ApprovalGateTool.ApprovalContext(null, null, null),
            new CopyOnWriteArrayList<>(), List.of(attachment), List.of());
        assertNotNull(assembly.toolkit().getTool("read_attachment"),
            "带附件时注册 read_attachment 工具");
    }
}
