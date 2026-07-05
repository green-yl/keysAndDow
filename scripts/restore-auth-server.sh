#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/keysAndDwd}"
DB_FILE="${DB_FILE:-$APP_DIR/data/database.sqlite}"
SOURCE_CACHE_DIR="${SOURCE_CACHE_DIR:-/srv/source_cache}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
LOG_FILE="${LOG_FILE:-$APP_DIR/logs/maintenance.log}"
CONTAINER_NAME="${CONTAINER_NAME:-keysAndDwd-service}"

usage() {
  echo "用法: $0 <备份目录>"
  echo "示例: $0 /opt/keysAndDwd/backups/daily/20260705-030000"
}

[ "${1:-}" ] || { usage; exit 2; }
RESTORE_DIR="$1"
[ -d "$RESTORE_DIR" ] || { echo "备份目录不存在: $RESTORE_DIR"; exit 1; }
[ -f "$RESTORE_DIR/database.sqlite" ] || { echo "备份数据库不存在: $RESTORE_DIR/database.sqlite"; exit 1; }

mkdir -p "$(dirname "$LOG_FILE")" "$BACKUP_DIR/pre-restore"

log() {
  echo "[$(date '+%F %T')] [restore] $*" | tee -a "$LOG_FILE"
}

STAMP="$(date +%Y%m%d-%H%M%S)"
SAFETY_DIR="$BACKUP_DIR/pre-restore/$STAMP"
mkdir -p "$SAFETY_DIR"

docker_cmd="docker"
if ! docker ps >/dev/null 2>&1; then
  docker_cmd="sudo docker"
fi

log "开始恢复: $RESTORE_DIR"

if [ -f "$DB_FILE" ]; then
  command -v python3 >/dev/null 2>&1 || { echo "未找到 python3"; exit 1; }
  python3 - "$DB_FILE" "$SAFETY_DIR/database.sqlite.before-restore" <<'PY'
import sqlite3, sys
src, dst = sys.argv[1], sys.argv[2]
source = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
target = sqlite3.connect(dst)
try:
    source.backup(target)
finally:
    target.close()
    source.close()
PY
  log "已备份当前数据库: $SAFETY_DIR/database.sqlite.before-restore"
fi

if [ -d "$SOURCE_CACHE_DIR" ]; then
  tar -C "$(dirname "$SOURCE_CACHE_DIR")" -czf "$SAFETY_DIR/source_cache.before-restore.tar.gz" "$(basename "$SOURCE_CACHE_DIR")"
  log "已备份当前源码缓存: $SAFETY_DIR/source_cache.before-restore.tar.gz"
fi

$docker_cmd stop "$CONTAINER_NAME" >/dev/null 2>&1 || true

cp "$RESTORE_DIR/database.sqlite" "$DB_FILE"
log "数据库已恢复"

if [ -f "$RESTORE_DIR/source_cache.tar.gz" ]; then
  rm -rf "$SOURCE_CACHE_DIR"
  mkdir -p "$(dirname "$SOURCE_CACHE_DIR")"
  tar -C "$(dirname "$SOURCE_CACHE_DIR")" -xzf "$RESTORE_DIR/source_cache.tar.gz"
  log "源码缓存已恢复"
fi

$docker_cmd start "$CONTAINER_NAME" >/dev/null
sleep 5
code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:3003/api/health 2>/dev/null || echo 000)"
if [ "$code" = "200" ]; then
  log "恢复完成，健康检查通过"
else
  log "恢复后健康检查异常: HTTP $code"
  exit 1
fi
