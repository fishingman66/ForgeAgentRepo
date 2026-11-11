package com.forgeagent.common.liteflow.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import com.forgeagent.common.core.exception.ServiceException;

/**
 * LiteFlow 上下文必填校验节点。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@LiteflowComponent("contextRequired")
public class ContextRequiredComponent extends NodeComponent {

    @Override
    public void process() {
        if (getFirstContextBean() == null) {
            throw new ServiceException("LiteFlow 上下文不能为空");
        }
    }

}
