package com.forgeagent.agent.service.runner;

/**
 * 08方案 / A3:免审批门标记。
 *
 * <p>仅读平台内部快照、无外部副作用、无敏感数据的元数据工具(如 SqlSchemaTool)
 * 经 Provider 装配时跳过审批门,与拆分前 ToolAssembler 的行为完全一致;
 * 绝不能挂到任何会发外部请求或有写副作用的工具上。</p>
 *
 * @author ForgeAgent
 */
public interface ApprovalExempt {
}
