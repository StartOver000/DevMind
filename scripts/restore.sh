#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."
if [ -z "$1" ]; then
  echo "用法: ./scripts/restore.sh backups/devmind_xxx.dump"
  exit 1
fi
docker cp "$1" devmind-postgres:/tmp/restore.dump
docker exec devmind-postgres pg_restore -U devmind -d devmind --clean --if-exists /tmp/restore.dump
echo "restore completed"
