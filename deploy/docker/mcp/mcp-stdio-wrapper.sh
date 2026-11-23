#!/bin/sh
# MCP STDIO working-directory 等价能力。
#
# 背景:AgentScope 2.0.5 的 StdioTransportConfig 与 MCP ServerParameters(2.2.0)
#       均无 cwd 字段,StdioClientTransport 也未调用 ProcessBuilder.directory(),
#       子进程默认继承 JVM 工作目录。本脚本在 exec 前切到 MCP_WORKDIR。
#
# 用法(MCP server 记录三字段):
#   command = /opt/forge/mcp/mcp-stdio-wrapper.sh   # 须在 allowed-commands 白名单内
#   args    = ["/opt/forge/mcp/tools/<工具脚本>"]   # 须命中该 basename 的 argsPatterns
#   env     = {"MCP_WORKDIR": "/opt/forge/work"}    # 目标工作目录,必须已存在
#
# 安全前提(必读):
#   1. McpStdioCommandValidator 只校验配置字符串,不读取本脚本内容;
#      本文件里的 $ 与 && 不会被元字符闸拦截,这是方案成立的前提,也是攻击面。
#   2. commandName 会变成 mcp-stdio-wrapper.sh,原解释器命令上的 argsPatterns
#      与内联代码闸都不再生效,必须给本 basename 单独配置严格的 argsPatterns。
#   3. 白名单按 basename 也匹配,同名脚本可命中;脚本文件须最小权限且不可被普通用户替换。
#
# 建议:argsPatterns 只允许 /opt/forge/mcp/tools/ 下的固定脚本,不要放行 node/python 等解释器,
#       否则 -e/--eval 一类内联代码会连闸跳过。
set -eu

if [ -z "${MCP_WORKDIR:-}" ]; then
  echo "mcp-stdio-wrapper: MCP_WORKDIR is not set" >&2
  exit 2
fi
if [ ! -d "$MCP_WORKDIR" ]; then
  echo "mcp-stdio-wrapper: MCP_WORKDIR is not a directory: $MCP_WORKDIR" >&2
  exit 2
fi
if [ "$#" -eq 0 ]; then
  echo "mcp-stdio-wrapper: no command specified" >&2
  exit 2
fi

cd "$MCP_WORKDIR"
exec "$@"
