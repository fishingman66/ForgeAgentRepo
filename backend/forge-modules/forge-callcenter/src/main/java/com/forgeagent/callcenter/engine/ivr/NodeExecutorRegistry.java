package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行器注册表(C3):收集容器内全部 {@link NodeExecutor} Bean,按 type 路由,
 * 对齐 SmartCall NodeGranterFactory(Bean 自动发现)。
 */
@Component
public class NodeExecutorRegistry {

    private final Map<String, NodeExecutor> byType = new HashMap<>();

    public NodeExecutorRegistry(List<NodeExecutor> executors) {
        for (NodeExecutor executor : executors) {
            if (byType.put(executor.type(), executor) != null) {
                throw new ServiceException("IVR 节点执行器类型重复: " + executor.type());
            }
        }
    }

    public NodeExecutor get(String type) {
        NodeExecutor executor = type == null ? null : byType.get(type);
        if (executor == null) {
            throw new ServiceException("IVR 节点执行器未装配: " + type + ",已装配: " + byType.keySet());
        }
        return executor;
    }
}
