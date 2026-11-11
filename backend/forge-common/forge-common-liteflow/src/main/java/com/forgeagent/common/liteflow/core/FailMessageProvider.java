package com.forgeagent.common.liteflow.core;

/**
 * LiteFlow 失败提示提供者。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public interface FailMessageProvider {

    /**
     * 获取公共失败节点抛出的业务提示。
     *
     * @return 失败提示
     */
    String getFailMessage();

}
