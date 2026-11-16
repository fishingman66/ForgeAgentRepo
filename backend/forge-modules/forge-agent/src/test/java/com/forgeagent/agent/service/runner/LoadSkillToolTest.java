package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.service.IAgentSkillService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 08方案 / A2:load_skill 懒加载工具契约单测。
 *
 * <p>锁定四条铁律:白名单外名称一律拒绝(装配时快照兜底);实时状态优先于快照
 * (停用/卸载立即失效);手册全文 + 提示词片段完整返回;缺参给出可操作提示。</p>
 */
@Tag("dev")
class LoadSkillToolTest {

    private static ToolCallParam input(Map<String, Object> input) {
        ToolCallParam param = mock(ToolCallParam.class);
        Mockito.when(param.getInput()).thenReturn(input);
        return param;
    }

    private static AgentSkill skill() {
        AgentSkill s = new AgentSkill();
        s.setId(9L);
        s.setName("退换货处理");
        s.setDescription("引导用户完成退换货申请");
        s.setVersion(2);
        s.setStatus("0");
        s.setInstructions("1. 确认订单号;\n2. 校验退货资格;\n3. 创建工单。");
        s.setPromptFragment("全程保持安抚语气,不承诺赔偿金额。");
        return s;
    }

    @Test
    void whitelistedSkillLoadsFullManualAndFragment() {
        IAgentSkillService service = mock(IAgentSkillService.class);
        when(service.getInstalledByName(7L, "退换货处理")).thenReturn(skill());
        LoadSkillTool tool = new LoadSkillTool(service, 7L,
            List.of(new ToolAssembler.SkillDef("退换货处理", "摘要")));

        String text = textOf(tool.callAsync(input(Map.of("name", "退换货处理"))).block());
        assertTrue(text.contains("[技能手册 退换货处理 v2]"), "必须带技能名与版本");
        assertTrue(text.contains("校验退货资格"), "手册全文必须返回");
        assertTrue(text.contains("[技能约束]"), "提示词片段以技能约束段返回");
        assertTrue(text.contains("不承诺赔偿金额"));
    }

    @Test
    void unknownSkillIsRejectedBySnapshotWhitelist() {
        IAgentSkillService service = mock(IAgentSkillService.class);
        when(service.getInstalledByName(7L, "退换货处理")).thenReturn(skill());
        LoadSkillTool tool = new LoadSkillTool(service, 7L,
            List.of(new ToolAssembler.SkillDef("别的技能", "x")));

        String text = textOf(tool.callAsync(input(Map.of("name", "退换货处理"))).block());
        assertTrue(text.contains("未安装或名称无效"),
            "白名单快照外名称直接拒绝,不触发查库");
        Mockito.verify(service, Mockito.never()).getInstalledByName(7L, "退换货处理");
    }

    @Test
    void realtimeDisabledStateWinsOverSnapshot() {
        IAgentSkillService service = mock(IAgentSkillService.class);
        AgentSkill disabled = skill();
        disabled.setStatus("1");
        when(service.getInstalledByName(7L, "退换货处理")).thenReturn(disabled);
        LoadSkillTool tool = new LoadSkillTool(service, 7L,
            List.of(new ToolAssembler.SkillDef("退换货处理", "摘要")));

        String text = textOf(tool.callAsync(input(Map.of("name", "退换货处理"))).block());
        assertTrue(text.contains("当前不可用"),
            "技能停用后实时状态优先,装配快照只负责白名单");
        assertFalse(text.contains("校验退货资格"), "停用技能不得泄露手册内容");
    }

    @Test
    void missingOrBlankNameGivesActionableHint() {
        IAgentSkillService service = mock(IAgentSkillService.class);
        LoadSkillTool tool = new LoadSkillTool(service, 7L,
            List.of(new ToolAssembler.SkillDef("s", "x")));

        assertTrue(textOf(tool.callAsync(input(Map.of())).block()).contains("缺少 name 参数"));
        assertTrue(textOf(tool.callAsync(input(Map.of("name", "  "))).block()).contains("缺少 name 参数"),
            "空白名称 trim 后等价缺参,给可操作提示");
        assertTrue(tool.getDescription().contains("操作手册"), "描述必须自我说明加载语义");
    }


    private static String textOf(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().getFirst()).getText();
    }
}
