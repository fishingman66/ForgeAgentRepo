package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.McpStdioProperties;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P3 / C2:MCP STDIO 命令准入校验。
 *
 * <p>四道闸(全部 fail-closed):</p>
 * <ol>
 *   <li><b>白名单</b>——{@code agent.mcp.stdio.allowed-commands} 为空视为未启用,直接拒绝;</li>
 *   <li><b>元字符</b>——命令与参数都不得含 {@code ; & | ` $ < >} 与换行(即使 ProcessBuilder 不走 shell,
 *       也不给"把配置当脚本"留口子);</li>
 *   <li><b>内联代码</b>——解释器({@code python/node/sh/bash...})携带 {@code -c}/{@code -e}/{@code --eval}
 *       默认拒绝,否则等于白名单形同虚设;</li>
 *   <li><b>非空参数</b>——null 参数会被替换成空串前先拒绝,避免拼出意外命令。</li>
 * </ol>
 *
 * <p>注意:本类只做"配置期"校验。运行期由 {@link ToolAssembler#buildMcpWrapper} 复验同一份规则,
 * 防止直改 DB 或历史脏数据绕过。</p>
 *
 * @author ForgeAgent
 */
public final class McpStdioCommandValidator {

    /** shell 元字符与换行:出现即拒 */
    private static final Pattern SHELL_META = Pattern.compile("[;&|`$<>\\n\\r]");

    /** 解释器的内联代码开关 */
    private static final Set<String> INLINE_CODE_FLAGS = Set.of(
        "-c", "-e", "--eval", "-eval", "--command", "--execute", "/c", "/k");

    /** 一旦携带内联代码开关就等于任意代码执行的解释器 */
    private static final Set<String> INTERPRETERS = Set.of(
        "sh", "bash", "zsh", "dash", "ksh", "ash", "cmd", "cmd.exe", "powershell", "powershell.exe",
        "pwsh", "pwsh.exe", "python", "python3", "python.exe", "node", "nodejs", "node.exe",
        "perl", "ruby", "php", "lua", "deno", "bun");

    private McpStdioCommandValidator() {
    }

    /**
     * 校验 stdio 命令与参数,不通过直接抛业务异常。
     *
     * @param command 可执行命令(命令名或绝对路径)
     * @param args    启动参数(可为空列表)
     * @param props   白名单与内联代码开关
     */
    public static void validate(String command, List<String> args, McpStdioProperties props) {
        List<String> allowed = props == null ? null : props.getAllowedCommands();
        if (allowed == null || allowed.isEmpty()) {
            throw new ServiceException("stdio 传输未启用:请先在 agent.mcp.stdio.allowed-commands 配置允许的命令");
        }
        if (StringUtils.isBlank(command)) {
            throw new ServiceException("stdio 传输必须指定命令");
        }
        if (SHELL_META.matcher(command).find()) {
            throw new ServiceException("stdio 命令含非法字符(不允许 shell 元字符)");
        }
        String commandName = baseName(command).toLowerCase(Locale.ROOT);
        boolean matched = false;
        for (String item : allowed) {
            if (StringUtils.isBlank(item)) {
                continue;
            }
            if (item.trim().equals(command)) {
                matched = true;
                break;
            }
            if (baseName(item.trim()).equalsIgnoreCase(commandName)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new ServiceException("stdio 命令不在白名单内:" + command);
        }

        List<String> argv = args == null ? List.of() : args;
        for (String arg : argv) {
            if (arg == null) {
                throw new ServiceException("stdio 启动参数不能为 null");
            }
            if (SHELL_META.matcher(arg).find()) {
                throw new ServiceException("stdio 启动参数含非法字符(不允许 shell 元字符):" + arg);
            }
        }

        boolean inlineAllowed = props != null && props.isAllowInlineCode();
        if (!inlineAllowed && INTERPRETERS.contains(commandName)) {
            for (String arg : argv) {
                if (INLINE_CODE_FLAGS.contains(arg.toLowerCase(Locale.ROOT))) {
                    throw new ServiceException("stdio 禁止解释器内联代码参数(" + arg
                        + "):如需放行请显式设置 agent.mcp.stdio.allow-inline-code=true");
                }
            }
        }

        // 第五闸(C2):args 白名单正则。仅对配置了 argsPatterns 的命令生效,未配置的命令放行
        // (保持存量兼容);每个参数都必须命中至少一条正则,非法正则直接拒绝(避免闸门静默失效)。
        Map<String, List<String>> argsPatterns = props == null ? null : props.getArgsPatterns();
        if (argsPatterns != null && argsPatterns.containsKey(commandName)) {
            List<String> patterns = argsPatterns.get(commandName);
            if (patterns == null || patterns.isEmpty()) {
                throw new ServiceException("stdio 命令 " + commandName + " 配置了空的 argsPatterns,视为禁用");
            }
            List<Pattern> compiled = new ArrayList<>();
            for (String regex : patterns) {
                if (StringUtils.isBlank(regex)) {
                    throw new ServiceException("stdio argsPatterns 含空正则,命令=" + commandName);
                }
                try {
                    compiled.add(Pattern.compile(regex));
                } catch (Exception e) {
                    throw new ServiceException("stdio argsPatterns 正则非法(" + regex + "):" + e.getMessage());
                }
            }
            for (String arg : argv) {
                boolean argMatched = compiled.stream().anyMatch(p -> p.matcher(arg).matches());
                if (!argMatched) {
                    throw new ServiceException("stdio 参数不在白名单内(" + commandName + "):" + arg);
                }
            }
        }
    }

    /** 取路径最后一段:/usr/local/bin/node -> node;C:\\node.exe -> node.exe */
    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }
}
