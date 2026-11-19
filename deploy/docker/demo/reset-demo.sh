#!/usr/bin/env bash
# ============================================================================
# ForgeAgent 演示环境每日重置
#
# 用法：cd deploy/docker && bash demo/reset-demo.sh
# 定时：0 4 * * * cd /path/to/forge-agent/deploy/docker && bash demo/reset-demo.sh >> /var/log/forge-demo-reset.log 2>&1
#
# 做了什么：
#   1) 执行 demo/reset.sql —— 清 demo 账号与演示空间产生的运行时数据
#      （会话/消息/运行轨迹/记忆与图谱/客服与语音业务数据/用量与日志/市场安装/评测执行记录）
#   2) 执行 mysql/init/03-demo.sql + 04-seed-agent.sql —— 幂等恢复演示账号/空间/
#      示例 Agent/数据源，以及供应商、内置工具、客服与语音域默认配置
#   3) 清空 Redis 缓存
#
# 注意：
#   - 库名以容器内 MYSQL_DATABASE 为准（不再硬编码 forge-vue）
#   - Redis 密码从宿主机 .env 读取：redis 服务未在 compose 里声明 environment，
#     容器内取不到 $REDIS_PASSWORD（原脚本因此静默失败且被 || true 掩盖）
#   - 用 MYSQL_PWD 传密码，避免命令行明文密码警告
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

[ -f .env ] || { log "缺少 .env：请先 cp .env.example .env 并按需修改"; exit 1; }

# 只取需要的两个键，不 source 整个 .env（JAVA_OPTS 这类含空格的值会让 source 出错）
env_val() { grep -E "^$1=" .env | tail -n1 | cut -d= -f2-; }
MYSQL_PW="${MYSQL_ROOT_PASSWORD:-$(env_val MYSQL_ROOT_PASSWORD)}"
REDIS_PW="${REDIS_PASSWORD:-$(env_val REDIS_PASSWORD)}"
[ -n "$MYSQL_PW" ] || { log "MYSQL_ROOT_PASSWORD 为空，中止"; exit 1; }

DB_NAME="$(docker compose exec -T mysql sh -c 'printf %s "${MYSQL_DATABASE:-forge-vue}"')"
DB_NAME="${DB_NAME:-forge-vue}"

log "开始重置演示环境（库：$DB_NAME）"

log "1/3 清理运行时数据（demo/reset.sql）"
docker compose exec -T -e MYSQL_PWD="$MYSQL_PW" mysql \
  mysql -uroot --default-character-set=utf8mb4 "$DB_NAME" < demo/reset.sql

log "2/3 恢复演示种子（03-demo.sql + 04-seed-agent.sql，均幂等）"
docker compose exec -T -e MYSQL_PWD="$MYSQL_PW" mysql \
  mysql -uroot --default-character-set=utf8mb4 "$DB_NAME" < mysql/init/03-demo.sql
# 04 承载供应商/内置工具/意图/敏感词/质检/渠道/IVR/路由。reset.sql 不删这些配置,
# 但若被人工误删,这里能一并恢复;渠道走 ON DUPLICATE KEY,会把回退话术重置为默认值,
# 对「每日回到初始演示态」是预期行为。
docker compose exec -T -e MYSQL_PWD="$MYSQL_PW" mysql \
  mysql -uroot --default-character-set=utf8mb4 "$DB_NAME" < mysql/init/04-seed-agent.sql

log "3/3 清空 Redis 缓存"
if [ -n "$REDIS_PW" ]; then
  if docker compose exec -T redis redis-cli -a "$REDIS_PW" FLUSHDB >/dev/null 2>&1; then
    log "  Redis 已清空"
  else
    log "  警告：Redis 清理失败（不影响数据库重置，请检查 REDIS_PASSWORD）"
  fi
else
  log "  未配置 REDIS_PASSWORD，跳过"
fi

# 校验：重置后 demo 账号不应残留会话
LEFT="$(docker compose exec -T -e MYSQL_PWD="$MYSQL_PW" mysql \
  mysql -uroot -N -B --default-character-set=utf8mb4 "$DB_NAME" \
  -e "SELECT COUNT(*) FROM agent_session WHERE create_by = 1900000000000000001;")"
log "校验：残留演示会话 ${LEFT:-?} 条"
[ "${LEFT:-0}" = "0" ] || log "警告：仍有残留会话，请检查 demo/reset.sql"

log "演示环境重置完成"
