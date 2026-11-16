package com.forgeagent.agent.service.runner;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.mapper.AgentSkillMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 08方案 / A5:技能对外 MCP 工具契约单测。
 *
 * <p>锁定:list 不含手册大字段、get 按名称精确命中、停用/不存在报业务错误、
 * 定义覆盖两个工具且 schema 必填 name。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SkillMcpExternalToolProviderTest {

    private final ObjectMapper om = new ObjectMapper();

    @Mock
    private AgentSkillMapper skillMapper;

    @InjectMocks
    private SkillMcpExternalToolProvider provider;

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito 环境无 Spring 启动,MP lambda 列名缓存需预热
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentSkill.class);
    }

    private static AgentSkill skill(String name, String description) {
        AgentSkill s = new AgentSkill();
        s.setId(9L);
        s.setName(name);
        s.setDescription(description);
        s.setVersion(2);
        s.setStatus("0");
        s.setInstructions("1. 确认订单号;\n2. 创建工单。");
        s.setPromptFragment("保持安抚语气。");
        s.setToolIds("1,2");
        return s;
    }

    @Test
    void supportedToolsCoverListAndGet() {
        assertEquals(2, provider.supportedTools().size());
        assertTrue(provider.supportedTools().contains("skill_list"));
        assertTrue(provider.supportedTools().contains("skill_get"));
        assertEquals(2, provider.definitions(om).size(), "definitions 必须同时给出两个工具");
    }

    @Test
    void skillListReturnsRowsWithoutManualBody() {
        when(skillMapper.selectList(any())).thenReturn(List.of(
            skill("退换货处理", "引导完成退换货申请"), skill("物流查询", "查询物流轨迹")));

        JsonNode result = provider.invoke(om, "skill_list", om.createObjectNode());
        JsonNode rows = result.path("rows");
        assertEquals(2, rows.size());
        assertEquals("退换货处理", rows.get(0).path("name").asText());
        assertEquals("shared", rows.get(0).path("scope").asText());
        assertFalse(rows.get(0).has("instructions"), "list 不得携带手册大字段");
        assertTrue(result.path("hint").asText().contains("skill_get"));
    }

    @Test
    void skillGetReturnsFullManualAndFragment() throws Exception {
        when(skillMapper.selectOne(any())).thenReturn(skill("退换货处理", "摘要"));

        JsonNode args = om.readTree("{\"name\":\"退换货处理\"}");
        JsonNode result = provider.invoke(om, "skill_get", args);
        JsonNode row = result.path("rows").get(0);
        assertTrue(row.path("instructions").asText().contains("创建工单"), "get 返回手册全文");
        assertTrue(row.path("promptFragment").asText().contains("安抚"), "提示词片段随手册下发");
        assertEquals("1,2", row.path("toolIds").asText(), "引用工具ID供外部 Agent 理解能力边界");
    }

    @Test
    void skillGetRejectsMissingOrUnknownSkill() {
        when(skillMapper.selectOne(any())).thenReturn(null);

        JsonNode blankArgs = om.createObjectNode();
        assertThrows(IllegalArgumentException.class, () -> provider.invoke(om, "skill_get", blankArgs),
            "缺 name 参数必须抛业务校验异常");
        JsonNode unknownArgs = om.createObjectNode().put("name", "不存在的技能");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> provider.invoke(om, "skill_get", unknownArgs),
            "未知/停用技能必须抛业务校验异常(端点转为 isError 透出消息)");
        assertTrue(ex.getMessage().contains("不存在或已停用"));
    }
}
