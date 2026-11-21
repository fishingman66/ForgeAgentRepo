package com.forgeagent.agent.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4:MCP SSRF 守护默认值与白名单启动校验单测。锁四件事:
 * ① 默认开启守护(防御公网攻击面);② 默认白名单含回环与 RFC1918/ULA、不含链路本地;
 * ③ 开启但白名单为空时启动失败(fail-fast,避免无效守护);④ 关闭时不校验白名单。
 */
@Tag("dev")
class AgentRuntimePropertiesSsrfTest {

    @Test
    void guardEnabledByDefaultWithSensibleAllowlist() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        assertTrue(props.isMcpSsrfGuardEnabled(), "D4 默认应开启 SSRF 守护");
        List<String> allow = props.getMcpSsrfAllowlist();
        assertTrue(allow.contains("127.0.0.0/8"), "默认白名单必须含回环(本地 MCP 最常见部署)");
        assertTrue(allow.contains("10.0.0.0/8"), "默认白名单必须含 RFC1918 常用段");
        assertTrue(allow.contains("fc00::/7"), "默认白名单必须含 IPv6 ULA");
        assertTrue(allow.stream().noneMatch(e -> e.startsWith("169.254")),
            "云元数据链路本地段不得默认放行");
        assertTrue(allow.stream().noneMatch(e -> e.startsWith("100.64")),
            "CGNAT 段不得默认放行");
        assertDoesNotThrow(props::afterPropertiesSet, "默认配置必须能通过启动校验");
    }

    @Test
    void enabledWithEmptyAllowlistFailsFast() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setMcpSsrfAllowlist(List.of());
        assertThrows(IllegalStateException.class, props::afterPropertiesSet,
            "开启守护但白名单为空=无效守护,必须启动失败");
        props.setMcpSsrfAllowlist(null);
        assertThrows(IllegalStateException.class, props::afterPropertiesSet);
    }

    @Test
    void disabledGuardSkipsAllowlistValidation() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setMcpSsrfGuardEnabled(false);
        props.setMcpSsrfAllowlist(List.of());
        assertDoesNotThrow(props::afterPropertiesSet);
    }
}
