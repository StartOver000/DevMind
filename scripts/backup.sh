#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."
mkdir -p backups
timestamp=$(date +%Y%m%d_%H%M%S)
name="devmind_${timestamp}.dump"
docker exec devmind-postgres pg_dump -U devmind -d devmind -F c -f "/tmp/${name}"
docker cp "devmind-postgres:/tmp/${name}" "backups/${name}"
echo "backup created: backups/${name}"
