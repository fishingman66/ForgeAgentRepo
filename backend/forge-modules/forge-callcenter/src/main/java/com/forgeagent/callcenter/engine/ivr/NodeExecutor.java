package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.common.core.exception.ServiceException;

/**
 * 节点执行器 SPI(C3):type 与 {@link IvrNode} 鉴别器一致,注册表按此路由;
 * 执行器必须无状态(会话资源经 {@link NodeContext} 变量传入)。
 */
public interface NodeExecutor {

    /** 节点类型,与 IvrNode 子类的 type() 一致。 */
    String type();

    /**
     * 执行节点:实现应只在业务异常时抛 ServiceException(驱动器落死信),
     * 分支类离开一律用 NodeOutcome 表达。
     */
    NodeOutcome execute(NodeContext ctx, IvrNode node) throws Exception;

    /** 类型安全取节点(注册表保证 node 与本执行器 type 匹配)。 */
    @SuppressWarnings("unchecked")
    default <T extends IvrNode> T cast(NodeContext ctx, IvrNode node, Class<T> clazz) {
        if (!clazz.isInstance(node)) {
            throw new ServiceException("节点类型不匹配: 期望 " + clazz.getSimpleName() + " 实际 " + node.getClass().getSimpleName());
        }
        return (T) node;
    }
}
