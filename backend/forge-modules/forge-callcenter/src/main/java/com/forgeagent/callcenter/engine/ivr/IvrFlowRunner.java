package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.common.core.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流程驱动循环(C3,对齐 SmartCall NodeState 的同步 while 语义):
 * 当前节点 → 执行器 → nextId,直至 null 或挂机;步数上限防流程配置死循环。
 *
 * <p>挂机退出:事件链路(CHANNEL_HANGUP)置 hangupFlag,或驱动线程被
 * interrupt(ESL outbound 断开);两种方式均让循环在下个节点边界退出。</p>
 */
@Slf4j
public class IvrFlowRunner {

    /** 步数上限:9 种节点无子流程,200 步足以覆盖合法流程,超出即配置死循环。 */
    private static final int MAX_STEPS = 200;

    /**
     * 驱动流程执行。
     *
     * @param nodes      已索引流程(IvrFlowService.load)
     * @param ctx        会话上下文(变量已装配)
     * @param hangupFlag 挂机标志(事件链路置位)
     * @return 终止原因:completed(走到 nextId=null)/interrupted(挂机或线程中断)
     */
    public String run(Map<String, IvrNode> nodes, String startId, NodeContext ctx, AtomicBoolean hangupFlag) {
        String currentId = startId;
        for (int step = 0; step < MAX_STEPS; step++) {
            if (Thread.currentThread().isInterrupted() || hangupFlag.get()) {
                log.debug("[CC][IVR] 流程被挂机中断 step={}", step);
                return "interrupted";
            }
            IvrNode node = currentId == null ? null : nodes.get(currentId);
            if (node == null) {
                if (currentId != null) {
                    throw new ServiceException("IVR 流程节点不存在: " + currentId);
                }
                return "completed";
            }
            NodeExecutorRegistry registry = registry(nodes, ctx);
            NodeExecutor executor = registry.get(node.type());
            NodeOutcome outcome;
            try {
                outcome = executor.execute(ctx, node);
            } catch (ServiceException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            } catch (Exception e) {
                throw new ServiceException("IVR 节点执行失败[" + node.getId() + "/" + node.type() + "]: " + e.getMessage());
            }
            ctx.setVar("ivrLastResult", outcome.result());
            currentId = outcome.nextId();
            if (currentId == null) {
                return "completed";
            }
        }
        throw new ServiceException("IVR 流程疑似死循环,超过步数上限 " + MAX_STEPS);
    }

    /** 注册表经上下文传入(驱动器装配);避免 runner 自身成为 Spring 依赖。 */
    @SuppressWarnings("unchecked")
    private NodeExecutorRegistry registry(Map<String, IvrNode> nodes, NodeContext ctx) {
        Object value = ctx.getVar("executorRegistry");
        if (!(value instanceof NodeExecutorRegistry registry)) {
            throw new ServiceException("上下文缺少 executorRegistry,检查会话驱动器装配");
        }
        return registry;
    }
}
