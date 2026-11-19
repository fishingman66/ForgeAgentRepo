package com.forgeagent.customerservice.controller;

import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能客服模块健康检查(S0 装配冒烟用)。
 *
 * <p>{@code agent.customer-service.enabled=false} 时本端点不注册,返回 404。</p>
 */
@ConditionalOnCustomerServiceEnabled
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/customerservice")
public class CsHealthController extends BaseController {

    private final CsModuleProperties properties;

    @GetMapping("/health")
    public R<com.forgeagent.customerservice.domain.vo.CsHealthVo> health() {
        com.forgeagent.customerservice.domain.vo.CsHealthVo data = new com.forgeagent.customerservice.domain.vo.CsHealthVo();
        data.setModule("forge-customer-service");
        data.setEnabled(properties.getEnabled());
        data.setTransport(properties.getTransport());
        data.setStage("S0");
        return R.ok(data);
    }
}
