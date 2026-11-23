package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.McpStdioProperties;
import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A-6:STDIO working-directory wrapper 与现有五闸的兼容性 + 安全边界。
 *
 * <p>wrapper 是部署侧脚本,validator 看不到它的内容,所以这里验证两件事:
 * ① 正确的 wrapper 配置能通过且元字符/argsPatterns 闸仍生效;
 * ② 明确记录两个残余风险(内联码闸因 commandName 变化而跳过、白名单 basename 口径),
 *    防止后续误以为绝对路径白名单是严格文件锁定。</p>
 */
@Tag("dev")
class McpStdioWrapperCompatibilityTest {

    private static final String WRAPPER = "/opt/forge/mcp/mcp-stdio-wrapper.sh";
    private static final String TOOL = "/opt/forge/mcp/tools/server.sh";

    private static McpStdioProperties props() {
        McpStdioProperties p = new McpStdioProperties();
        p.setAllowedCommands(new ArrayList<>(List.of(WRAPPER)));
        // A-6 安全前提:只允许 tools 目录下的固定脚本,不放行解释器
        p.setArgsPatterns(new HashMap<>(Map.of(
            "mcp-stdio-wrapper.sh", List.of("^/opt/forge/mcp/tools/[A-Za-z0-9_.-]+$"))));
        return p;
    }

    @Test
    @DisplayName("A-6:wrapper 绝对路径 + 白名单工具脚本通过校验")
    void wrapperConfigPasses() {
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(WRAPPER, List.of(TOOL), props()));
    }

    @Test
    @DisplayName("A-6:元字符闸对 wrapper 参数仍生效")
    void shellMetaInArgStillRejected() {
        assertThrows(ServiceException.class,
            () -> McpStdioCommandValidator.validate(WRAPPER, List.of(TOOL + ";id"), props()));
    }

    @Test
    @DisplayName("A-6:严格 argsPatterns 挡住 wrapper 参数越界")
    void argOutsidePatternRejected() {
        assertThrows(ServiceException.class,
            () -> McpStdioCommandValidator.validate(WRAPPER, List.of("/tmp/evil.sh"), props()));
    }

    @Test
    @DisplayName("A-6:内联码闸因 commandName 变化被跳过,由 argsPatterns 兜住")
    void inlineFlagBlockedOnlyByArgsPattern() {
        // commandName=mcp-stdio-wrapper.sh 不在 INTERPRETERS,闸3 不检查 -e;
        // 闸5 全串匹配失败仍拒绝——这正是必须给 wrapper 配 argsPatterns 的原因。
        assertThrows(ServiceException.class,
            () -> McpStdioCommandValidator.validate(WRAPPER, List.of("-e", "process.exit(0)"), props()));
    }

    @Test
    @DisplayName("A-6:白名单 basename 口径允许同名脚本命中(残余风险,锁住事实)")
    void basenameAliasStillMatches() {
        // 绝对路径白名单并非文件级锁定:任意目录下同名 mcp-stdio-wrapper.sh 都能命中闸1。
        // 部署侧必须限制脚本文件权限与 PATH;本用例用于防止后来者误判白名单强度。
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(
            "/tmp/untrusted/mcp-stdio-wrapper.sh", List.of(TOOL), props()));
    }

    @Test
    @DisplayName("A-6:脚本内容含 shell 元字符但配置字符串不含,校验不受影响")
    void validatorOnlyChecksConfigStrings() {
        Path fromModule = Path.of("../../../deploy/docker/mcp/mcp-stdio-wrapper.sh");
        Path fromRoot = Path.of("deploy/docker/mcp/mcp-stdio-wrapper.sh");
        Path script = Files.exists(fromModule) ? fromModule : fromRoot;
        assumeTrue(Files.exists(script), "wrapper 脚本不在预期路径,跳过内容断言");
        String content = assertDoesNotThrow(() -> Files.readString(script));
        assertTrue(content.contains("&&"), "脚本应含 cd && exec");
        assertTrue(content.contains("$MCP_WORKDIR"), "脚本应读 MCP_WORKDIR");

        // 元字符只存在于脚本文件里,不在 command/args 配置字符串中,validator 天然看不到
        assertDoesNotThrow(() -> McpStdioCommandValidator.validate(WRAPPER, List.of(TOOL), props()));
    }
}
