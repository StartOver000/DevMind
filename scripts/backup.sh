#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."

# 备份保留份数（环境变量可覆盖，默认 14）
KEEP="${BACKUP_KEEP:-14}"
mkdir -p backups
timestamp=$(date +%Y%m%d_%H%M%S)
base="devmind_${timestamp}"

# 1) 数据库：pg_dump 自定义格式（schema + 数据）
docker exec devmind-postgres pg_dump -U devmind -d devmind -F c -f "/tmp/${base}.dump"
docker cp "devmind-postgres:/tmp/${base}.dump" "backups/${base}.dump"
docker exec devmind-postgres rm -f "/tmp/${base}.dump"
echo "db backup: backups/${base}.dump"

# 2) 文件数据：data/files（用户上传文档，pg_dump 不含）——打包保留目录结构
if [ -d "data/files" ] && [ -n "$(ls -A data/files 2>/dev/null)" ]; then
  tar -czf "backups/${base}.files.tar.gz" -C data files
  echo "files backup: backups/${base}.files.tar.gz"
else
  echo "data/files 为空，跳过文件备份"
fi

# 3) 保留策略：只保留最近 KEEP 份（db 与 files 配套清理）
ls -1t backups/devmind_*.dump 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
ls -1t backups/devmind_*.files.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
echo "backup done, keep latest ${KEEP}"
