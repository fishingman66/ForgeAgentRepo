package com.forgeagent.agent.event;

import org.springframework.context.ApplicationEvent;

/**
 * 触发器配置变更事件:新增/修改/启停/删除后发布,供调度器刷新 Cron 注册
 *
 * @author ForgeAgent
 */
public class TriggerChangedEvent extends ApplicationEvent {

    public TriggerChangedEvent(Object source) {
        super(source);
    }
}
