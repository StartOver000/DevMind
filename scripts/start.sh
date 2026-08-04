#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."
if [ ! -f .env ]; then
  cp .env.example .env
  echo "已生成 .env，可修改模型配置"
fi
mvn -q -DskipTests package
docker compose build app
docker compose up -d postgres app
echo "DevMind 启动中：http://localhost:8080"
