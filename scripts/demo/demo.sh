#!/usr/bin/env bash
# DevMind 一键演示启动（Linux/macOS）
# 用法：
#   ./scripts/demo/demo.sh                 # 一键演示（数据已存在则跳过导入）
#   ./scripts/demo/demo.sh --reset         # 强制重置并重导演示数据
#   ./scripts/demo/demo.sh --port 8090     # 自定义端口
set -euo pipefail
cd "$(dirname "$0")/../.."

PORT="${PORT:-8090}"
RESET="${RESET:-false}"
DB_HOST="${DB_HOST:-devmind-postgres}"
DB_USER="${DB_USER:-devmind}"
DB_NAME="${DB_NAME:-devmind}"
DUMP_FILE="scripts/demo/demo-data.sql"

log() { printf '\n=== %s ===\n' "$1"; }

# 阶段 1：PostgreSQL
log '阶段 1/5：确保 PostgreSQL 容器运行'
if ! docker ps --format '{{.Names}}' | grep -q "^${DB_HOST}$"; then
  docker compose up -d postgres
fi
for i in $(seq 1 30); do
  if docker exec "$DB_HOST" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
    echo 'PostgreSQL 已就绪'; break
  fi
  [ "$i" = 30 ] && { echo '等待 PostgreSQL 就绪超时'; exit 1; }
  sleep 1
done

# 阶段 2：构建
log '阶段 2/5：Maven 构建（跳过测试）'
mvn -q -DskipTests package

# 阶段 3：启动应用（mock 模型，1024 维）
log '阶段 3/5：启动 DevMind（mock 模型，1024 维）'
JAR=$(ls -t target/devmind-*.jar | grep -v -E 'sources|original' | head -1)
[ -z "$JAR" ] && { echo '未找到 target/devmind-*.jar'; exit 1; }
mkdir -p logs
if lsof -i :"$PORT" >/dev/null 2>&1; then
  echo "端口 $PORT 已被占用，跳过启动"
else
  nohup java -jar "$JAR" --server.port="$PORT" --devmind.model-mode=mock \
    --devmind.embedding-dimensions=1024 \
    --devmind.security.rate-limit-per-minute=100000 \
    > logs/demo.out.log 2>&1 &
  echo "应用已后台启动，日志：logs/demo.out.log"
fi

# 阶段 4：健康检查
log '阶段 4/5：等待应用就绪'
READY=false
for i in $(seq 1 60); do
  if curl -sf "http://localhost:${PORT}/actuator/health" >/dev/null 2>&1; then
    READY=true; break
  fi
  sleep 2
done
[ "$READY" = false ] && { echo '健康检查超时，查看 logs/demo.err.log'; exit 1; }
echo '应用已就绪'

# 阶段 5：演示数据
log '阶段 5/5：演示数据检查 / 导入'
count_rows() { docker exec "$DB_HOST" psql -U "$DB_USER" -d "$DB_NAME" -t -A -c "SELECT count(*) FROM $1"; }
KB_COUNT=$(count_rows knowledge_base)

if [ "$RESET" = true ]; then
  TABLES="tenant, app_user, team, team_member, knowledge_base, knowledge_base_member, document, document_chunk, document_version, document_task, tool_definition, tool_grant, tool_semantic, skill, workflow, agent_memory, sql_diagnosis, mcp_server"
  docker exec "$DB_HOST" psql -U "$DB_USER" -d "$DB_NAME" -c "TRUNCATE $TABLES CASCADE" >/dev/null
  KB_COUNT=0
fi

if [ "$KB_COUNT" -gt 0 ]; then
  echo "检测到已有演示数据（knowledge_base=$KB_COUNT），跳过导入。如需重置加 --reset"
else
  [ -f "$DUMP_FILE" ] || { echo "未找到演示数据快照 $DUMP_FILE"; exit 1; }
  echo "导入演示数据快照 $DUMP_FILE ..."
  docker exec -i "$DB_HOST" psql -U "$DB_USER" -d "$DB_NAME" < "$DUMP_FILE"
  echo '演示数据导入完成'
fi

# 演示清单
log '演示就绪'
echo "  访问地址 : http://localhost:${PORT}"
echo "  知识库   : $(count_rows knowledge_base) 个 | 文档 $(count_rows document) 篇 | 切片 $(count_rows document_chunk) 条"
echo "  模型模式 : mock（不产生真实 AI 费用，embedding 1024 维）"
echo "  体验建议 :"
echo "    1. 检索  POST /api/knowledge-bases/19/search  {\"question\":\"什么是 Prompt Engineering\",\"topK\":3}"
echo "    2. 问答  POST /api/knowledge-bases/19/chat    {\"question\":\"MCP 协议是什么\"}"
echo "    3. 工作流 POST /api/workflows/11/run           （自动运维日报）"
