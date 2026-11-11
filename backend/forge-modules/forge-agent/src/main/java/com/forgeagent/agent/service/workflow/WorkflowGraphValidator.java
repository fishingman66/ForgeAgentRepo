package com.forgeagent.agent.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.vo.WorkflowValidateVo;
import com.forgeagent.common.core.utils.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流图静态校验器(体检质量基建批次从 AgentWorkflowServiceImpl 提取)。
 *
 * <p>覆盖:节点基础与类型专属校验、连线规则、条件分支标注、失败跳转目标、
 * 并行分支结构与唯一汇聚点、DAG 无环、起止连通性。除 ObjectMapper 外无外部依赖,
 * 可脱离 Spring 上下文单测。</p>
 *
 * @author ForgeAgent
 */
public class WorkflowGraphValidator {

    public static final Set<String> VALID_NODE_TYPES = Set.of(
        "start", "llm", "tool", "knowledge", "condition", "approval", "end",
        "loop", "code", "http", "parallel", "join"
    );

    public static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

    /**
     * 条件/并行节点的出边(带分支标注):条件出边按 branchName 关联分支(W6),
     * 并行出边即分支起点(W7)。执行引擎与校验器共用同一结构。
     */
    public record OutEdge(String target, String branchName) {
    }

    private final ObjectMapper objectMapper;

    public WorkflowGraphValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WorkflowValidateVo validateWorkflow(String workflowJson) {
        WorkflowValidateVo vo = new WorkflowValidateVo();
        List<WorkflowValidateVo.Issue> blockers = new ArrayList<>();
        List<WorkflowValidateVo.Issue> warnings = new ArrayList<>();
        vo.setBlockers(blockers);
        vo.setWarnings(warnings);

        JsonNode root;
        try {
            root = objectMapper.readTree(workflowJson);
        } catch (Exception e) {
            blockers.add(issue(null, null, "工作流 JSON 解析失败:" + e.getMessage()));
            vo.setValid(false);
            return vo;
        }

        JsonNode nodesNode = root.path("nodes");
        JsonNode edgesNode = root.path("edges");
        if (!nodesNode.isArray() || nodesNode.isEmpty()) {
            blockers.add(issue(null, null, "画布必须至少包含一个开始节点"));
            vo.setValid(false);
            return vo;
        }

        Map<String, JsonNode> nodeMap = new HashMap<>();
        Set<String> nodeNames = new HashSet<>();
        int startCount = 0;
        int endCount = 0;
        String startId = null;
        String endId = null;

        // 第一遍:节点基础校验
        for (JsonNode node : nodesNode) {
            String id = node.path("id").asText("");
            String type = node.path("type").asText("");
            String name = node.path("name").asText("");

            if (id.isBlank()) {
                blockers.add(issue(null, null, "存在缺少 ID 的节点"));
                continue;
            }
            if (nodeMap.containsKey(id)) {
                blockers.add(issue(id, null, "节点 ID 重复:" + id));
                continue;
            }
            nodeMap.put(id, node);

            if (!VALID_NODE_TYPES.contains(type)) {
                blockers.add(issue(id, null, "未知节点类型:" + type));
                continue;
            }
            if (name.isBlank()) {
                blockers.add(issue(id, null, "节点名称不能为空"));
            } else if (!nodeNames.add(name)) {
                blockers.add(issue(id, null, "节点名称重复:" + name));
            } else if (name.length() < 2 || name.length() > 20) {
                warnings.add(issue(id, null, "节点名称建议 2-20 字符:" + name));
            }

            if ("start".equals(type)) {
                startCount++;
                startId = id;
            } else if ("end".equals(type)) {
                endCount++;
                endId = id;
            }

            // 类型专属校验
            switch (type) {
                case "llm" -> {
                    if (node.path("config").path("modelId").isMissingNode() || node.path("config").path("modelId").isNull()) {
                        blockers.add(issue(id, null, "LLM 节点必须绑定模型:" + name));
                    }
                }
                case "tool" -> {
                    if (node.path("config").path("toolId").isMissingNode() || node.path("config").path("toolId").isNull()) {
                        blockers.add(issue(id, null, "工具节点必须绑定工具:" + name));
                    } else if (StringUtils.isBlank(node.path("config").path("args").asText(null))
                        && StringUtils.isBlank(node.path("config").path("paramMapping").asText(null))) {
                        // 工具节点无参数时以空参执行,必填参数的工具会在运行期失败,提前给出提示
                        warnings.add(issue(id, null, "工具节点未配置参数(args),将以空参数执行:" + name));
                    }
                }
                case "knowledge" -> {
                    if (node.path("config").path("knowledgeId").isMissingNode() || node.path("config").path("knowledgeId").isNull()) {
                        blockers.add(issue(id, null, "知识库检索节点必须绑定知识库:" + name));
                    }
                }
                case "loop" -> {
                    if (node.path("config").path("modelId").isMissingNode() || node.path("config").path("modelId").isNull()) {
                        blockers.add(issue(id, null, "循环节点必须绑定模型:" + name));
                    }
                    if (StringUtils.isBlank(node.path("config").path("items").asText(null))) {
                        blockers.add(issue(id, null, "循环节点必须配置迭代集合(支持 {{变量}} 引用):" + name));
                    }
                    if (StringUtils.isBlank(node.path("config").path("prompt").asText(null))) {
                        blockers.add(issue(id, null, "循环节点必须配置循环体提示词(支持 {{item}} 引用):" + name));
                    }
                }
                case "code" -> {
                    if (StringUtils.isBlank(node.path("config").path("code").asText(null))) {
                        blockers.add(issue(id, null, "代码节点必须填写 Python 代码:" + name));
                    }
                }
                case "http" -> {
                    String url = node.path("config").path("url").asText("");
                    if (StringUtils.isBlank(url)) {
                        blockers.add(issue(id, null, "HTTP 节点必须配置请求地址:" + name));
                    } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        blockers.add(issue(id, null, "HTTP 节点请求地址必须以 http(s):// 开头:" + name));
                    }
                    String method = node.path("config").path("method").asText("GET").toUpperCase();
                    if (!HTTP_METHODS.contains(method)) {
                        blockers.add(issue(id, null, "HTTP 节点请求方法不支持:" + method));
                    }
                }
                default -> {
                }
            }
        }

        if (startCount == 0) {
            blockers.add(issue(null, null, "画布必须包含开始节点"));
        } else if (startCount > 1) {
            blockers.add(issue(null, null, "P0 仅允许一个开始节点"));
        }
        if (endCount == 0) {
            blockers.add(issue(null, null, "画布必须包含结束节点"));
        } else if (endCount > 1) {
            blockers.add(issue(null, null, "P0 仅允许一个结束节点"));
        }

        // 第二遍:连线校验
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, List<OutEdge>> adjOut = new HashMap<>();
        Map<String, List<String>> reverseAdj = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (JsonNode edge : edgesNode) {
            String eid = edge.path("id").asText("");
            String source = edge.path("source").asText("");
            String target = edge.path("target").asText("");
            if (!eid.isBlank() && !edgeIds.add(eid)) {
                blockers.add(issue(null, eid, "连线 ID 重复:" + eid));
            }
            if (!nodeMap.containsKey(source)) {
                blockers.add(issue(null, eid, "连线源节点不存在:" + source));
                continue;
            }
            if (!nodeMap.containsKey(target)) {
                blockers.add(issue(null, eid, "连线目标节点不存在:" + target));
                continue;
            }
            if (source.equals(target)) {
                blockers.add(issue(null, eid, "不允许自环连线:" + source));
            }
            String sourceType = nodeMap.get(source).path("type").asText("");
            String targetType = nodeMap.get(target).path("type").asText("");
            if ("end".equals(sourceType)) {
                blockers.add(issue(source, eid, "结束节点不能有出边"));
            }
            if ("start".equals(targetType)) {
                blockers.add(issue(target, eid, "开始节点不能有入边"));
            }
            // 普通节点出边唯一(条件/并行节点允许多出边)
            if (!"condition".equals(sourceType) && !"parallel".equals(sourceType)) {
                List<String> outs = adj.getOrDefault(source, new ArrayList<>());
                if (!outs.isEmpty()) {
                    blockers.add(issue(source, eid, "非条件节点只能有一条出边:" + nodeMap.get(source).path("name").asText("")));
                }
            }
            adj.computeIfAbsent(source, k -> new ArrayList<>()).add(target);
            adjOut.computeIfAbsent(source, k -> new ArrayList<>())
                .add(new OutEdge(target, edge.path("branchName").asText("")));
            reverseAdj.computeIfAbsent(target, k -> new ArrayList<>()).add(source);
        }

        // 条件节点分支标注校验
        for (JsonNode node : nodesNode) {
            if (!"condition".equals(node.path("type").asText(""))) {
                continue;
            }
            String id = node.path("id").asText("");
            JsonNode branches = node.path("config").path("branches");
            if (!branches.isArray() || branches.isEmpty()) {
                blockers.add(issue(id, null, "条件节点必须配置至少一个分支"));
                continue;
            }
            Set<String> branchNames = new HashSet<>();
            for (JsonNode b : branches) {
                String bn = b.path("name").asText("");
                if (bn.isBlank()) {
                    blockers.add(issue(id, null, "分支名称不能为空"));
                } else if (!branchNames.add(bn)) {
                    blockers.add(issue(id, null, "分支名称重复:" + bn));
                }
            }
            List<String> outs = adj.getOrDefault(id, new ArrayList<>());
            if (outs.size() != branches.size()) {
                blockers.add(issue(id, null, "条件节点出边数量必须与分支数一致"));
            }
            // W6:出边分支名关联校验 —— 要么全标注,要么全不标注(兼容旧数据下标对齐)
            List<OutEdge> outEdges = adjOut.getOrDefault(id, new ArrayList<>());
            long named = outEdges.stream().filter(e -> StringUtils.isNotBlank(e.branchName())).count();
            if (named > 0 && named < outEdges.size()) {
                blockers.add(issue(id, null, "条件节点出边必须全部标注分支名或全部不标注"));
            } else if (named == outEdges.size() && !outEdges.isEmpty()) {
                Set<String> edgeNames = new HashSet<>();
                for (OutEdge e : outEdges) {
                    if (!edgeNames.add(e.branchName())) {
                        blockers.add(issue(id, null, "条件节点出边分支名重复:" + e.branchName()));
                    }
                }
                for (JsonNode b : branches) {
                    String bn = b.path("name").asText("");
                    if (StringUtils.isNotBlank(bn) && !edgeNames.contains(bn)) {
                        blockers.add(issue(id, null, "分支未关联出边:" + bn));
                    }
                }
            }
        }

        // W5:失败跳转策略校验
        for (JsonNode node : nodesNode) {
            JsonNode cfg = node.path("config");
            if (!"goto".equals(cfg.path("failStrategy").asText(""))) {
                continue;
            }
            String id = node.path("id").asText("");
            String target = cfg.path("failureTargetNodeKey").asText("");
            if (StringUtils.isBlank(target)) {
                blockers.add(issue(id, null, "失败跳转策略必须指定目标节点:" + node.path("name").asText("")));
            } else if (!nodeMap.containsKey(target)) {
                blockers.add(issue(id, null, "失败跳转目标节点不存在:" + target));
            }
        }

        // W7:并行/汇聚节点校验
        for (JsonNode node : nodesNode) {
            String id = node.path("id").asText("");
            String type = node.path("type").asText("");
            if ("parallel".equals(type)) {
                List<String> outs = adj.getOrDefault(id, new ArrayList<>());
                if (outs.size() < 2) {
                    blockers.add(issue(id, null, "并行节点至少需要两条出边分支:" + node.path("name").asText("")));
                    continue;
                }
                Set<String> common = null;
                for (String start : outs) {
                    Set<String> reach = bfsUntilJoin(start, adj, nodeMap);
                    common = common == null ? new HashSet<>(reach) : common;
                    common.retainAll(reach);
                }
                List<String> joins = (common == null ? Set.<String>of() : common).stream()
                    .filter(nid -> "join".equals(nodeMap.get(nid).path("type").asText("")))
                    .sorted().toList();
                if (joins.isEmpty()) {
                    blockers.add(issue(id, null, "并行节点分支未汇聚到任何 JOIN 节点:" + node.path("name").asText("")));
                } else if (joins.size() > 1) {
                    blockers.add(issue(id, null, "并行节点分支汇聚到多个 JOIN 节点,请改为单汇入:" + node.path("name").asText("")));
                }
                for (String start : outs) {
                    for (String nid : bfsUntilJoin(start, adj, nodeMap)) {
                        String bt = nodeMap.get(nid).path("type").asText("");
                        String bn = nodeMap.get(nid).path("name").asText("");
                        if ("parallel".equals(bt)) {
                            blockers.add(issue(nid, null, "并行分支内不支持嵌套并行节点:" + bn));
                        } else if ("approval".equals(bt)) {
                            blockers.add(issue(nid, null, "并行分支内不支持人工审批节点:" + bn));
                        } else if ("condition".equals(bt)) {
                            blockers.add(issue(nid, null, "并行分支内不支持条件分支节点:" + bn));
                        }
                    }
                }
            } else if ("join".equals(type)) {
                List<String> ins = reverseAdj.getOrDefault(id, new ArrayList<>());
                if (ins.size() < 2) {
                    warnings.add(issue(id, null, "JOIN 节点入边少于 2 条,通常应为并行分支的汇聚点"));
                }
            }
        }

        // 环路检测(DAG)
        if (hasCycle(nodeMap.keySet(), adj)) {
            blockers.add(issue(null, null, "工作流存在环路,P0 仅支持 DAG"));
        }

        // 连通性:开始可达
        if (startId != null) {
            Set<String> reachable = bfs(startId, adj);
            for (String nid : nodeMap.keySet()) {
                if (!nid.equals(startId) && !reachable.contains(nid)) {
                    blockers.add(issue(nid, null, "节点从开始节点不可达:" + nodeMap.get(nid).path("name").asText("")));
                }
            }
        }
        // 连通性:可达结束
        if (endId != null) {
            Set<String> canReachEnd = bfs(endId, reverseAdj);
            for (String nid : nodeMap.keySet()) {
                if (!nid.equals(endId) && !canReachEnd.contains(nid)) {
                    blockers.add(issue(nid, null, "节点无法到达结束节点:" + nodeMap.get(nid).path("name").asText("")));
                }
            }
        }

        vo.setValid(blockers.isEmpty());
        return vo;
    }

    private static WorkflowValidateVo.Issue issue(String nodeId, String edgeId, String message) {
        WorkflowValidateVo.Issue i = new WorkflowValidateVo.Issue();
        i.setNodeId(nodeId);
        i.setEdgeId(edgeId);
        i.setMessage(message);
        return i;
    }

    private static boolean hasCycle(Set<String> nodes, Map<String, List<String>> adj) {
        Map<String, Integer> state = new HashMap<>(); // 0未访问 1访问中 2已完成
        for (String n : nodes) {
            if (state.getOrDefault(n, 0) == 0 && dfsCycle(n, adj, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfsCycle(String node, Map<String, List<String>> adj, Map<String, Integer> state) {
        state.put(node, 1);
        for (String next : adj.getOrDefault(node, new ArrayList<>())) {
            int s = state.getOrDefault(next, 0);
            if (s == 1) {
                return true;
            }
            if (s == 0 && dfsCycle(next, adj, state)) {
                return true;
            }
        }
        state.put(node, 2);
        return false;
    }

    /** 从起点可达的全部节点(执行引擎 W8 startNodeKey 上游标记与连通性校验共用) */
    public static Set<String> bfs(String start, Map<String, List<String>> adj) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.offer(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : adj.getOrDefault(cur, new ArrayList<>())) {
                if (visited.add(next)) {
                    queue.offer(next);
                }
            }
        }
        return visited;
    }

    /** W7:并行分支可达集 —— 到达 JOIN 节点即停止下探(用于校验分支内部节点类型与汇聚点) */
    private static Set<String> bfsUntilJoin(String start, Map<String, List<String>> adj, Map<String, JsonNode> nodeMap) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.offer(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            JsonNode node = nodeMap.get(cur);
            if (node != null && "join".equals(node.path("type").asText(""))) {
                continue;
            }
            for (String next : adj.getOrDefault(cur, new ArrayList<>())) {
                if (visited.add(next)) {
                    queue.offer(next);
                }
            }
        }
        return visited;
    }
}
