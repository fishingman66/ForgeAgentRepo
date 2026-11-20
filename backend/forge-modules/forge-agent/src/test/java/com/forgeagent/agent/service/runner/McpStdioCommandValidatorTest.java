package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.McpStdioProperties;
import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 / C2:STDIO 命令准入的四道闸。
 *
 * <p>这是安全关键用例:任何一条放松都等于"配置里能写任意命令执行任意代码"。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
@DisplayName("MCP STDIO 命令准入")
class McpStdioCommandValidatorTest {

    private McpStdioProperties props(String... commands) {
        McpStdioProperties p = new McpStdioProperties();
        p.setAllowedCommands(List.of(commands));
        return p;
    }

    @Test
    @DisplayName("白名单为空 → 一律拒绝(fail-closed,不是不校验)")
    void emptyAllowlistRejectsEverything() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> McpStdioCommandValidator.validate("node", List.of("server.js"), props()));
        assertTrue(ex.getMessage().contains("未启用"));
    }

    @Test
    @DisplayName("配置为 null → 同样拒绝")
    void nullPropsRejects() {
        assertThrows(ServiceException.class,
            () -> McpStdioCommandValidator.validate("node", List.of(), null));
    }

    @Test
    @DisplayName("白名单按命令名匹配,绝对路径同样命中")
    void allowlistMatchesByName() {
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(
            "node", List.of("server.js"), props("node")));
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(
            "/usr/local/bin/node", List.of("server.js"), props("node")));
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(
            "npx", List.of("-y", "@modelcontextprotocol/server-filesystem", "/data"), props("npx")));
    }

    @Test
    @DisplayName("未列出的命令被拒")
    void unknownCommandRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> McpStdioCommandValidator.validate("curl", List.of("http://evil"), props("node")));
        assertTrue(ex.getMessage().contains("白名单"));
    }

    @Test
    @DisplayName("shell 元字符:命令与参数都拒绝")
    void shellMetaRejected() {
        assertThrows(ServiceException.class, () -> McpStdioCommandValidator.validate(
            "node;rm -rf /", List.of(), props("node")));
        assertThrows(ServiceException.class, () -> McpStdioCommandValidator.validate(
            "node", List.of("a.js && curl evil"), props("node")));
        assertThrows(ServiceException.class, () -> McpStdioCommandValidator.validate(
            "node", List.of("$(whoami)"), props("node")));
        assertThrows(ServiceException.class, () -> McpStdioCommandValidator.validate(
            "node", List.of("a|b"), props("node")));
    }

    @Test
    @DisplayName("解释器内联代码默认拒绝(否则白名单形同虚设)")
    void inlineCodeRejectedByDefault() {
        assertThrows(ServiceException.class, () -> McpStdioCommandValidator.validate(
            "python", List.of("-c", "import os; os.system('id')"), props("python")));
        assertThrows(ServiceException.class, () -> McpStdioCommandValidator.validate(
            "node", List.of("-e", "require('child_process')"), props("node")));
    }

    @Test
    @DisplayName("显式放开 allow-inline-code 后放行(仍需无元字符)")
    void inlineCodeAllowedWhenConfigured() {
        McpStdioProperties p = props("python");
        p.setAllowInlineCode(true);
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(
            "python", List.of("-c", "print(1)"), p));
    }

    @Test
    @DisplayName("非解释器的 -c 参数不受内联代码规则影响")
    void nonInterpreterFlagAllowed() {
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(
            "mytool", List.of("-c", "config.yml"), props("mytool")));
    }

    @Test
    @DisplayName("空命令被拒")
    void blankCommandRejected() {
        assertThrows(ServiceException.class,
            () -> McpStdioCommandValidator.validate("  ", List.of(), props("node")));
    }

    @Test
    @DisplayName("args 中 null 元素被拒,避免拼出意外命令")
    void nullArgRejected() {
        List<String> withNull = new java.util.ArrayList<>();
        withNull.add("a.js");
        withNull.add(null);
        assertThrows(ServiceException.class,
            () -> McpStdioCommandValidator.validate("node", withNull, props("node")));
    }

    @Test
    @DisplayName("Windows 反斜杠路径同样按文件名匹配")
    void windowsPathMatches() {
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(
            "C:\\Program Files\\nodejs\\node.exe", List.of("server.js"), props("node.exe")));
    }

    @Test
    @DisplayName("stdio 参数解析:空/非法 JSON 退化为空列表")
    void parseArgsToleratesBlankAndBad() {
        assertEquals(List.of(), ToolAssembler.parseStdioArgs(null));
        assertEquals(List.of(), ToolAssembler.parseStdioArgs("  "));
        assertEquals(List.of("-y", "server"), ToolAssembler.parseStdioArgs("[\"-y\",\"server\"]"));
    }

    @Test
    @DisplayName("stdio env 解析:值统一转字符串,空/非法 JSON 退化为空表")
    void parseEnvToleratesBlankAndBad() {
        assertTrue(ToolAssembler.parseStdioEnv(null).isEmpty());
        assertEquals("1", ToolAssembler.parseStdioEnv("{\"FLAG\":1}").get("FLAG"));
        assertTrue(ToolAssembler.parseStdioEnv("not-json").isEmpty());
    }
}
