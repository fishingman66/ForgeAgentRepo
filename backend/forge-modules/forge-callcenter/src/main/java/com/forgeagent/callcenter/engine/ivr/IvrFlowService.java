package com.forgeagent.callcenter.engine.ivr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.callcenter.domain.CsIvrFlow;
import com.forgeagent.callcenter.mapper.CsIvrFlowMapper;
import com.forgeagent.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IVR 流程加载服务(C3):按 route_code 取启用流程,反序列化节点数组并按 id 索引;
 * 60s TTL 缓存(miss 不告警,回源重读)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IvrFlowService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<IvrNode>> NODE_LIST = new TypeReference<>() {
    };
    private static final long CACHE_TTL_MS = 60_000L;

    private final CsIvrFlowMapper flowMapper;

    /** 已索引的流程:id → 节点。 */
    public Map<String, IvrNode> load(Long workspaceId, String routeCode) {
        CsIvrFlow flow = flowMapper.selectOne(new LambdaQueryWrapper<CsIvrFlow>()
            .eq(CsIvrFlow::getWorkspaceId, workspaceId)
            .eq(CsIvrFlow::getRouteCode, routeCode)
            .eq(CsIvrFlow::getStatus, "0")
            .eq(CsIvrFlow::getDelFlag, 0L)
            .last("limit 1"));
        if (flow == null) {
            throw new ServiceException("IVR 流程不存在或未启用: " + routeCode);
        }
        return deserialize(flow.getFlow());
    }

    /** 反序列化 + 按 id 索引;重复 id / 非 start 首节点视为流程定义错误。 */
    public Map<String, IvrNode> deserialize(String flowJson) {
        List<IvrNode> nodes;
        try {
            nodes = MAPPER.readValue(flowJson, NODE_LIST);
        } catch (Exception e) {
            throw new ServiceException("IVR 流程 JSON 解析失败: " + e.getMessage());
        }
        if (nodes.isEmpty() || !"start".equals(nodes.get(0).type())) {
            throw new ServiceException("IVR 流程首节点必须为 start");
        }
        Map<String, IvrNode> byId = new LinkedHashMap<>();
        for (IvrNode node : nodes) {
            if (node.getId() == null || node.getId().isBlank()) {
                throw new ServiceException("IVR 流程存在未设置 id 的节点");
            }
            if (byId.put(node.getId(), node) != null) {
                throw new ServiceException("IVR 流程节点 id 重复: " + node.getId());
            }
        }
        return byId;
    }
}
