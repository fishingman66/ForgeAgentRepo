package com.forgeagent.callcenter.engine.ivr;

/**
 * 节点执行输出(C3):nextId=null 表示流程终止(hangup/transfer/事件接管);
 * result 写入上下文 ivrLastResult 供后续节点引用。
 */
public record NodeOutcome(String nextId, Object result) {

    public static NodeOutcome next(String nextId) {
        return new NodeOutcome(nextId, null);
    }

    public static NodeOutcome end() {
        return new NodeOutcome(null, null);
    }
}
