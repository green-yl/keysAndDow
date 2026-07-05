#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/keysAndDwd}"
DB_FILE="${DB_FILE:-$APP_DIR/data/database.sqlite}"
SOURCE_CACHE_DIR="${SOURCE_CACHE_DIR:-/srv/source_cache}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
LOG_FILE="${LOG_FILE:-$APP_DIR/logs/maintenance.log}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DAILY_DIR="$BACKUP_DIR/daily/$STAMP"

mkdir -p "$DAILY_DIR" "$(dirname "$LOG_FILE")"

log() {
  echo "[$(date '+%F %T')] [backup] $*" | tee -a "$LOG_FILE"
}

fail() {
  log "失败: $*"
  exit 1
}

command -v python3 >/dev/null 2>&1 || fail "未找到 python3，无法安全备份 SQLite"
[ -f "$DB_FILE" ] || fail "数据库不存在: $DB_FILE"

log "开始每日备份: $DAILY_DIR"

DB_BACKUP="$DAILY_DIR/database.sqlite"
python3 - "$DB_FILE" "$DB_BACKUP" <<'PY'
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

[ -s "$DB_BACKUP" ] || fail "数据库备份文件为空: $DB_BACKUP"
log "数据库备份完成: $DB_BACKUP"

if [ -d "$SOURCE_CACHE_DIR" ]; then
  tar -C "$(dirname "$SOURCE_CACHE_DIR")" -czf "$DAILY_DIR/source_cache.tar.gz" "$(basename "$SOURCE_CACHE_DIR")"
  log "源码缓存备份完成: $DAILY_DIR/source_cache.tar.gz"
else
  log "源码缓存目录不存在，跳过: $SOURCE_CACHE_DIR"
fi

find "$BACKUP_DIR/daily" -mindepth 1 -maxdepth 1 -type d -mtime +"$RETENTION_DAYS" -exec rm -rf {} + 2>/dev/null || true
log "每日备份完成，保留 ${RETENTION_DAYS} 天"
