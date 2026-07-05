#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/keysAndDwd}"
DB_FILE="${DB_FILE:-$APP_DIR/data/database.sqlite}"
SOURCE_CACHE_DIR="${SOURCE_CACHE_DIR:-/srv/source_cache}"
LOG_FILE="${LOG_FILE:-$APP_DIR/logs/maintenance.log}"
BASE_URL="${BASE_URL:-http://127.0.0.1:3003}"
DISK_PATH="${DISK_PATH:-$APP_DIR}"
MIN_FREE_PERCENT="${MIN_FREE_PERCENT:-10}"
CONTAINER_NAME="${CONTAINER_NAME:-keysAndDwd-service}"

mkdir -p "$(dirname "$LOG_FILE")"

log() {
  echo "[$(date '+%F %T')] [check] $*" | tee -a "$LOG_FILE"
}

status=0

docker_cmd="docker"
if ! docker ps >/dev/null 2>&1; then
  docker_cmd="sudo docker"
fi

if $docker_cmd inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null | grep -qx true; then
  log "容器运行正常: $CONTAINER_NAME"
else
  log "异常: 容器未运行: $CONTAINER_NAME"
  status=1
fi

for path in /api/health /api/system/ping; do
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 "$BASE_URL$path" 2>/dev/null || echo 000)"
  if [ "$code" = "200" ]; then
    log "接口正常: $path HTTP $code"
  else
    log "异常: $path HTTP $code"
    status=1
  fi
done

if [ -f "$DB_FILE" ]; then
  log "数据库文件存在: $DB_FILE"
else
  log "异常: 数据库文件不存在: $DB_FILE"
  status=1
fi

if [ -d "$SOURCE_CACHE_DIR" ]; then
  log "源码缓存目录存在: $SOURCE_CACHE_DIR"
else
  log "异常: 源码缓存目录不存在: $SOURCE_CACHE_DIR"
  status=1
fi

free_percent="$(df -P "$DISK_PATH" | awk 'NR==2 {gsub("%", "", $5); print 100-$5}')"
if [ "${free_percent:-0}" -ge "$MIN_FREE_PERCENT" ]; then
  log "磁盘空间正常: ${free_percent}% 可用"
else
  log "异常: 磁盘空间不足，仅 ${free_percent}% 可用"
  status=1
fi

exit "$status"
