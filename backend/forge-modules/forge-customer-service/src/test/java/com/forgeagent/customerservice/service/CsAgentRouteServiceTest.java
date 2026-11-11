package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.domain.CsAgentRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** P3:多 Agent 路由匹配口径(渠道级优先 / 同级按 priority / 停用忽略)。 */
@Tag("dev")
class CsAgentRouteServiceTest {

    private CsAgentRoute route(String channelCode, String intent, Long agentId, int priority, String enabled) {
        CsAgentRoute route = new CsAgentRoute();
        route.setChannelCode(channelCode);
        route.setIntentCode(intent);
        route.setAgentId(agentId);
        route.setPriority(priority);
        route.setEnabled(enabled);
        return route;
    }

    @Test
    void noRouteReturnsNull() {
        assertNull(CsAgentRouteService.pick(List.of(), "CS_A", "RETURN_REFUND"));
        assertNull(CsAgentRouteService.pick(null, "CS_A", "RETURN_REFUND"));
        assertNull(CsAgentRouteService.pick(List.of(route(null, "RETURN_REFUND", 9L, 0, "1")), "CS_A", null));
    }

    @Test
    void channelScopedBeatsWorkspaceScoped() {
        List<CsAgentRoute> routes = List.of(
            route(null, "RETURN_REFUND", 100L, 99, "1"),
            route("CS_A", "RETURN_REFUND", 200L, 0, "1"));
        assertEquals(200L, CsAgentRouteService.pick(routes, "CS_A", "RETURN_REFUND"));
    }

    @Test
    void higherPriorityWinsAtSameScope() {
        List<CsAgentRoute> routes = List.of(
            route(null, "INVOICE", 1L, 1, "1"),
            route(null, "INVOICE", 2L, 5, "1"));
        assertEquals(2L, CsAgentRouteService.pick(routes, "CS_A", "INVOICE"));
    }

    @Test
    void disabledAndForeignChannelAreIgnored() {
        List<CsAgentRoute> routes = List.of(
            route("CS_A", "RETURN_REFUND", 1L, 9, "0"),
            route("CS_B", "RETURN_REFUND", 2L, 9, "1"));
        assertNull(CsAgentRouteService.pick(routes, "CS_A", "RETURN_REFUND"));
    }

    @Test
    void intentMustMatchExactly() {
        List<CsAgentRoute> routes = List.of(route(null, "RETURN_REFUND", 7L, 0, "1"));
        assertNull(CsAgentRouteService.pick(routes, "CS_A", "ORDER_STATUS"));
        assertEquals(7L, CsAgentRouteService.pick(routes, "CS_A", "RETURN_REFUND"));
    }
}
