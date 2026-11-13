package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.gateway.AiccGateway;
import com.forgeagent.callcenter.gateway.CsVoiceGateway;
import com.forgeagent.callcenter.gateway.FsMockGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * C1 网关路由表征测试:CallEventDispatcher 的唯一编排改动是「单一网关 → 按 provider 路由」,
 * 本测试锁死三条铁律——AICC 系仍路由到 AiccGateway、FS_MOCK 路由到 FsMockGateway、
 * FS 标识(真实 FsGateway 未部署)与多命中/零命中一律拒绝。既有 AICC 链路不得受路由化影响。
 */
@Tag("dev")
class CallEventDispatcherRoutingTest {

    private final List<CsVoiceGateway> gateways = List.of(new AiccGateway(), new FsMockGateway());

    @Test
    void aiccFamilyStillRoutesToAiccGateway() {
        assertEquals(AiccGateway.class, CallEventDispatcher.resolveGateway(gateways, "AICC").getClass());
        assertEquals(AiccGateway.class, CallEventDispatcher.resolveGateway(gateways, "AICC_MOCK").getClass());
        assertEquals(AiccGateway.class, CallEventDispatcher.resolveGateway(gateways, "aicc_mock").getClass());
    }

    @Test
    void fsMockRoutesToFsMockGateway() {
        assertEquals(FsMockGateway.class, CallEventDispatcher.resolveGateway(gateways, "FS_MOCK").getClass());
        assertEquals(FsMockGateway.class, CallEventDispatcher.resolveGateway(gateways, "fs_mock").getClass());
    }

    @Test
    void unroutableProvidersRejected() {
        // FS 需要真实 FsGateway(C1-real),Mock 不得顶替
        assertNull(CallEventDispatcher.resolveGateway(gateways, "FS"));
        assertNull(CallEventDispatcher.resolveGateway(gateways, "UNKNOWN"));
        assertNull(CallEventDispatcher.resolveGateway(gateways, null));
        assertNull(CallEventDispatcher.resolveGateway(gateways, ""));
    }

    @Test
    void duplicateSupportIsConfigurationError() {
        CsVoiceGateway duplicated = new AiccGateway();
        // 两个实现都声明支持 AICC_MOCK:多命中视为配置异常
        assertNull(CallEventDispatcher.resolveGateway(List.of(new AiccGateway(), duplicated), "AICC_MOCK"));
    }
}
