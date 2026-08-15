#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."

if [ -z "$1" ]; then
  echo "用法: ./scripts/restore.sh backups/devmind_xxx.dump [目标库]"
  echo "  目标库缺省为 devmind（生产库，会覆盖现有数据，慎用）"
  echo "  演练示例: ./scripts/restore.sh backups/devmind_xxx.dump devmind_restore_drill"
  exit 1
fi
DUMP="$1"
TARGET_DB="${2:-devmind}"

# 1) 文件数据恢复（配套 .files.tar.gz 存在时，还原到 data/files 目录结构；
#    注意 -C data：备份用 `tar -C data files` 打包，恢复必须解压到 data/ 下）
FILES="${DUMP%.dump}.files.tar.gz"
if [ -f "$FILES" ]; then
  mkdir -p data
  tar -xzf "$FILES" -C data
  echo "files restored from $FILES"
else
  echo "未找到配套文件备份: $FILES（仅恢复数据库）"
fi

# 2) 演练模式：目标库不是 devmind 时先建临时库（不触碰生产库）
if [ "$TARGET_DB" != "devmind" ]; then
  docker exec devmind-postgres psql -U devmind -d postgres -c "DROP DATABASE IF EXISTS ${TARGET_DB}" >/dev/null
  docker exec devmind-postgres psql -U devmind -d postgres -c "CREATE DATABASE ${TARGET_DB}" >/dev/null
  echo "演练目标库已创建: ${TARGET_DB}"
fi

# 3) 恢复数据库
if [ "$TARGET_DB" = "devmind" ]; then
  # 生产库：自定义格式恢复前清空 schema，保证幂等覆盖
  docker exec devmind-postgres psql -U devmind -d devmind -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null
fi
docker cp "$DUMP" devmind-postgres:/tmp/restore.dump
docker exec devmind-postgres pg_restore -U devmind -d "$TARGET_DB" --clean --if-exists /tmp/restore.dump
docker exec devmind-postgres rm -f /tmp/restore.dump
echo "restore completed -> ${TARGET_DB}"
