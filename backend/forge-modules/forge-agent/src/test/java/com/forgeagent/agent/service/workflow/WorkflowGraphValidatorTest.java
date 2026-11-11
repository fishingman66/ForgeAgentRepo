package com.forgeagent.agent.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.vo.WorkflowValidateVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流图校验器单测(质量基建批次)。
 *
 * <p>真实调用 {@link WorkflowGraphValidator#validateWorkflow} 锁定校验契约——
 * 此前的"测试"是在测试类里复制生产逻辑后对断言 JSON,对回归零价值。</p>
 */
@Tag("dev")
class WorkflowGraphValidatorTest {

    private final WorkflowGraphValidator validator = new WorkflowGraphValidator(new ObjectMapper());

    private static String node(String id, String type, String name, String configJson) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":\"" + name
            + "\",\"config\":" + configJson + "}";
    }

    private static String edge(String id, String source, String target) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"target\":\"" + target + "\"}";
    }

    private static String graph(List<String> nodes, List<String> edges) {
        return "{\"nodes\":[" + String.join(",", nodes) + "],\"edges\":["
            + String.join(",", edges) + "]}";
    }

    private static List<String> blockerMessages(WorkflowValidateVo vo) {
        return vo.getBlockers().stream().map(WorkflowValidateVo.Issue::getMessage).toList();
    }

    private static List<String> warningMessages(WorkflowValidateVo vo) {
        return vo.getWarnings().stream().map(WorkflowValidateVo.Issue::getMessage).toList();
    }

    private WorkflowValidateVo validate(List<String> nodes, List<String> edges) {
        return validator.validateWorkflow(graph(nodes, edges));
    }

    // ------------------------------------------------------------ 合法图

    @Test
    void minimalValidGraphPasses() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("l", "llm", "推理", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "l"), edge("e2", "l", "e")));
        assertTrue(vo.getValid(), "blockers=" + blockerMessages(vo));
        assertTrue(blockerMessages(vo).isEmpty());
    }

    // ------------------------------------------------------------ 节点基础

    @Test
    void unknownNodeTypeBlocked() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("x", "magic", "魔法", "{}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "x"), edge("e2", "x", "e")));
        assertFalse(vo.getValid());
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("未知节点类型:magic")));
    }

    @Test
    void missingOrMultipleStartEndBlocked() {
        WorkflowValidateVo vo = validate(
            List.of(node("l", "llm", "推理", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "l", "e")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("开始节点")));

        WorkflowValidateVo vo2 = validate(
            List.of(node("s1", "start", "开始1", "{}"),
                node("s2", "start", "开始2", "{}"),
                node("e", "end", "结束", "{}")),
            List.of());
        assertTrue(blockerMessages(vo2).stream().anyMatch(m -> m.contains("仅允许一个开始节点")));
    }

    @Test
    void duplicateNodeIdAndNameBlocked() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("s", "llm", "推理", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}")),
            List.of());
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("节点 ID 重复")));

        WorkflowValidateVo vo2 = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("l1", "llm", "推理", "{\"modelId\":1}"),
                node("l2", "llm", "推理", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}")),
            List.of());
        assertTrue(blockerMessages(vo2).stream().anyMatch(m -> m.contains("节点名称重复")));
    }

    // ------------------------------------------------------------ 连线规则

    @Test
    void cycleBlocked() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("a", "llm", "甲", "{\"modelId\":1}"),
                node("b", "llm", "乙", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "a"), edge("e2", "a", "b"),
                edge("e3", "b", "a"), edge("e4", "b", "e")));
        assertFalse(vo.getValid());
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("环路")));
    }

    @Test
    void selfLoopBlocked() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("a", "llm", "甲", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "a"), edge("e2", "a", "a"), edge("e3", "a", "e")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("自环")));
    }

    @Test
    void normalNodeMultipleOutEdgesBlocked() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("l", "llm", "推理", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "l"), edge("e2", "s", "e"), edge("e3", "l", "e")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("只能有一条出边")));
    }

    @Test
    void endOutEdgeAndStartInEdgeBlocked() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "e"), edge("e2", "e", "s")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("结束节点不能有出边")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("开始节点不能有入边")));
    }

    // ------------------------------------------------------------ 连通性

    @Test
    void unreachableNodesBlockedBothDirections() {
        // c 与主链断开:既从 start 不可达,也无法到达 end
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("l", "llm", "推理", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}"),
                node("c", "code", "孤岛", "{\"code\":\"print(1)\"}")),
            List.of(edge("e1", "s", "l"), edge("e2", "l", "e")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("从开始节点不可达")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("无法到达结束节点")));
    }

    // ------------------------------------------------------------ 条件分支标注(W6)

    @Test
    void conditionUnnamedEdgesPass() {
        // 全不标注 = 兼容旧数据的下标对齐模式,不应报错
        WorkflowValidateVo vo = validate(List.of(
                node("s", "start", "开始", "{}"),
                node("c", "condition", "条件", "{\"branches\":[{\"name\":\"A\",\"condition\":\"x == 1\"},{\"name\":\"B\",\"condition\":\"x == 2\"}]}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "c"),
                edge("ea", "c", "e"),
                edge("eb", "c", "e")));
        assertTrue(blockerMessages(vo).stream().noneMatch(m -> m.contains("分支名或全部不标注")),
            "blockers=" + blockerMessages(vo));
    }

    @Test
    void conditionPartiallyNamedEdgesExplicitlyBlocked() {
        List<String> nodes = List.of(
            node("s", "start", "开始", "{}"),
            node("c", "condition", "条件", "{\"branches\":[{\"name\":\"A\",\"condition\":\"x == 1\"},{\"name\":\"B\",\"condition\":\"x == 2\"}]}"),
            node("e", "end", "结束", "{}"));
        // 一条标注一条不标注 → 混合标注报错
        String edges = "[{\"id\":\"e1\",\"source\":\"s\",\"target\":\"c\"},"
            + "{\"id\":\"e2\",\"source\":\"c\",\"target\":\"e\",\"branchName\":\"A\"},"
            + "{\"id\":\"e3\",\"source\":\"c\",\"target\":\"e\"}]";
        WorkflowValidateVo vo = validator.validateWorkflow(graph(nodes, List.of()).replace(
            "\"edges\":[]", "\"edges\":" + edges));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("分支名或全部不标注")));
    }

    @Test
    void conditionNamedEdgeNotMatchingBranchBlocked() {
        List<String> nodes = List.of(
            node("s", "start", "开始", "{}"),
            node("c", "condition", "条件", "{\"branches\":[{\"name\":\"A\",\"condition\":\"x == 1\"},{\"name\":\"B\",\"condition\":\"x == 2\"}]}"),
            node("e", "end", "结束", "{}"));
        String edges = "[{\"id\":\"e1\",\"source\":\"s\",\"target\":\"c\"},"
            + "{\"id\":\"e2\",\"source\":\"c\",\"target\":\"e\",\"branchName\":\"A\"},"
            + "{\"id\":\"e3\",\"source\":\"c\",\"target\":\"e\",\"branchName\":\"C\"}]";
        WorkflowValidateVo vo = validator.validateWorkflow(graph(nodes, List.of()).replace(
            "\"edges\":[]", "\"edges\":" + edges));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("分支未关联出边:B")));
    }

    // ------------------------------------------------------------ 并行(W7)

    @Test
    void parallelWithSingleJoinPasses() {
        WorkflowValidateVo vo = validate(List.of(
                node("s", "start", "开始", "{}"),
                node("p", "parallel", "并行", "{}"),
                node("l1", "llm", "分支甲", "{\"modelId\":1}"),
                node("l2", "llm", "分支乙", "{\"modelId\":1}"),
                node("j", "join", "汇聚", "{}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e0", "s", "p"),
                edge("e1", "p", "l1"), edge("e2", "p", "l2"),
                edge("e3", "l1", "j"), edge("e4", "l2", "j"),
                edge("e5", "j", "e")));
        assertTrue(vo.getValid(), "blockers=" + blockerMessages(vo));
    }

    @Test
    void parallelWithoutJoinBlocked() {
        WorkflowValidateVo vo = validate(List.of(
                node("s", "start", "开始", "{}"),
                node("p", "parallel", "并行", "{}"),
                node("l1", "llm", "分支甲", "{\"modelId\":1}"),
                node("l2", "llm", "分支乙", "{\"modelId\":1}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e0", "s", "p"),
                edge("e1", "p", "l1"), edge("e2", "p", "l2"),
                edge("e3", "l1", "e"), edge("e4", "l2", "e")));
        assertFalse(vo.getValid());
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("未汇聚到任何 JOIN")));
    }

    @Test
    void parallelBranchWithApprovalBlocked() {
        WorkflowValidateVo vo = validate(List.of(
                node("s", "start", "开始", "{}"),
                node("p", "parallel", "并行", "{}"),
                node("ap", "approval", "审批", "{}"),
                node("l2", "llm", "分支乙", "{\"modelId\":1}"),
                node("j", "join", "汇聚", "{}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e0", "s", "p"),
                edge("e1", "p", "ap"), edge("e2", "p", "l2"),
                edge("e3", "ap", "j"), edge("e4", "l2", "j"),
                edge("e5", "j", "e")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("并行分支内不支持人工审批")));
    }

    @Test
    void joinWithFewInEdgesWarns() {
        WorkflowValidateVo vo = validate(List.of(
                node("s", "start", "开始", "{}"),
                node("j", "join", "汇聚", "{}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e0", "s", "j"), edge("e1", "j", "e")));
        assertTrue(warningMessages(vo).stream().anyMatch(m -> m.contains("入边少于 2 条")));
    }

    // ------------------------------------------------------------ 其他

    @Test
    void gotoTargetMustExist() {
        WorkflowValidateVo vo = validate(List.of(
                node("s", "start", "开始", "{}"),
                node("l", "llm", "推理", "{\"modelId\":1,\"failStrategy\":\"goto\",\"failureTargetNodeKey\":\"ghost\"}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "l"), edge("e2", "l", "e")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("失败跳转目标节点不存在")));
    }

    @Test
    void emptyGraphBlocked() {
        WorkflowValidateVo vo = validator.validateWorkflow("{\"nodes\":[],\"edges\":[]}");
        assertFalse(vo.getValid());
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("至少包含一个开始节点")));
    }

    @Test
    void malformedJsonBlocked() {
        WorkflowValidateVo vo = validator.validateWorkflow("{not-json");
        assertFalse(vo.getValid());
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("JSON 解析失败")));
    }

    @Test
    void llmWithoutModelBlocked() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("l", "llm", "推理", "{}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "l"), edge("e2", "l", "e")));
        assertTrue(blockerMessages(vo).stream().anyMatch(m -> m.contains("LLM 节点必须绑定模型")));
    }

    @Test
    void httpNodeValidation() {
        WorkflowValidateVo vo = validate(
            List.of(node("s", "start", "开始", "{}"),
                node("h", "http", "请求", "{\"url\":\"ftp://x\",\"method\":\"TRACE\"}"),
                node("e", "end", "结束", "{}")),
            List.of(edge("e1", "s", "h"), edge("e2", "h", "e")));
        List<String> messages = blockerMessages(vo);
        assertTrue(messages.stream().anyMatch(m -> m.contains("http(s):// 开头")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("请求方法不支持:TRACE")));
    }
}
