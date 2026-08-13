# DevMind 一键演示启动（Windows PowerShell）
# =====================================================
# 用途：周六演示一条龙 —— 起 PostgreSQL → 构建 → 启动应用（mock 模型）
#      → 幂等导入演示数据（空库才导 / -Reset 强制重导）→ 健康检查 → 打印演示清单
#
# 用法：
#   .\scripts\demo\demo.ps1                # 一键演示（数据已存在则跳过导入）
#   .\scripts\demo\demo.ps1 -Reset         # 强制重置并重导演示数据
#   .\scripts\demo\demo.ps1 -Port 8080     # 自定义端口
#   .\scripts\demo\demo.ps1 -NoBuild       # 跳过 Maven 构建（已构建过）
# =====================================================
param(
    [switch]$Reset,
    [int]$Port = 8090,
    [switch]$NoBuild,
    [switch]$RebuildVectors
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')

# ---------- 常量 ----------
$DB_HOST = 'devmind-postgres'
$DB_NAME = 'devmind'
$DB_USER = 'devmind'
$DUMP_FILE = 'scripts/demo/demo-data.sql'
$LOG_DIR = 'logs'
$DUMP_TABLES = @(
    'tenant','app_user','team','team_member',
    'knowledge_base','knowledge_base_member',
    'document','document_chunk','document_version','document_task',
    'tool_definition','tool_grant','tool_semantic',
    'skill','workflow','agent_memory','sql_diagnosis','mcp_server'
)
$EXTRA_ARGS = @('--server.port=' + $Port, '--devmind.model-mode=mock', '--devmind.embedding-dimensions=1024', '--devmind.security.rate-limit-per-minute=100000')
if ($RebuildVectors) { $EXTRA_ARGS += '--devmind.rebuild-vectors-on-start=true' }
$MOCK_ARGS = ($EXTRA_ARGS -join ' ')

function Write-Step([string]$msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# ---------- 阶段 1：PostgreSQL ----------
Write-Step '阶段 1/5：确保 PostgreSQL 容器运行'
if (-not (docker ps --format '{{.Names}}' | Select-String -Quiet $DB_HOST)) {
    Write-Host '容器未运行，启动中...'
    docker compose up -d postgres
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 容器启动失败' }
}
for ($i = 0; $i -lt 30; $i++) {
    $ready = docker exec $DB_HOST pg_isready -U $DB_USER -d $DB_NAME 2>$null
    if ($ready -match 'accepting') { Write-Host 'PostgreSQL 已就绪'; break }
    if ($i -eq 29) { throw '等待 PostgreSQL 就绪超时' }
    Start-Sleep -Seconds 1
}

# ---------- 阶段 2：构建 ----------
if (-not $NoBuild) {
    Write-Step '阶段 2/5：Maven 构建（跳过测试）'
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败' }
}

# ---------- 阶段 3：启动应用 ----------
Write-Step '阶段 3/5：启动 DevMind（mock 模型，1024 维）'
$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($existing) {
    Write-Host "端口 $Port 已被占用（PID $($existing.OwningProcess)），跳过启动"
} else {
    $jar = Get-ChildItem 'target\devmind-*.jar' | Where-Object { $_.Name -notmatch 'sources|original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw '未找到 target/devmind-*.jar，请先构建' }
    New-Item -ItemType Directory -Force $LOG_DIR | Out-Null
    $p = Start-Process java -ArgumentList @('-jar', $jar.FullName, $MOCK_ARGS) -WorkingDirectory (Get-Location) `
        -RedirectStandardOutput "$LOG_DIR\demo.out.log" -RedirectStandardError "$LOG_DIR\demo.err.log" -PassThru
    Write-Host "应用已启动（PID $($p.Id)），日志：$LOG_DIR\demo.out.log"
}

# ---------- 阶段 4：健康检查（等待表结构就绪） ----------
Write-Step '阶段 4/5：等待应用就绪'
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $null = Invoke-RestMethod -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 2
        $ready = $true; break
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $ready) { throw "应用 $Port 端口健康检查超时（60s），查看 $LOG_DIR\demo.err.log" }
Write-Host '应用已就绪'

# ---------- 阶段 5：演示数据 ----------
Write-Step '阶段 5/5：演示数据检查 / 导入'
function Get-DbCount([string]$table) {
    $v = docker exec $DB_HOST psql -U $DB_USER -d $DB_NAME -t -c "SELECT count(*) FROM $table" 2>$null
    return ([int]($v | Select-Object -First 1).Trim())
}
$kbCount = Get-DbCount 'knowledge_base'

if ($Reset) {
    Write-Host "[-Reset] 清空演示相关表（$($DUMP_TABLES.Count) 张）..."
    $tables = ($DUMP_TABLES -join ', ')
    docker exec $DB_HOST psql -U $DB_USER -d $DB_NAME -c "TRUNCATE $tables CASCADE" | Out-Null
    $kbCount = 0
}

if ($kbCount -gt 0) {
    Write-Host "检测到已有演示数据（knowledge_base=$kbCount），跳过导入。如需重置请加 -Reset"
} else {
    if (-not (Test-Path $DUMP_FILE)) { throw "未找到演示数据快照 $DUMP_FILE" }
    Write-Host "导入演示数据快照 $DUMP_FILE ..."
    $psqlCmd = "docker exec -i $DB_HOST psql -U $DB_USER -d $DB_NAME < $DUMP_FILE"
    cmd /c $psqlCmd
    if ($LASTEXITCODE -ne 0) { throw '演示数据导入失败（请确认应用以 1024 维启动，表结构已建）' }
    Write-Host '演示数据导入完成'
}

# ---------- 演示清单 ----------
Write-Step '演示就绪，清单如下'
$counts = @{}
foreach ($t in @('knowledge_base','document','document_chunk','tool_definition','workflow','skill','app_user')) {
    $counts[$t] = Get-DbCount $t
}
Write-Host @"

  访问地址 : http://localhost:$Port
  知识库   : $($counts['knowledge_base']) 个 | 文档 $($counts['document']) 篇 | 切片 $($counts['document_chunk']) 条
  接口工具 : $($counts['tool_definition']) 个 | 工作流 $($counts['workflow']) 个 | 技能 $($counts['skill']) 个 | 用户 $($counts['app_user']) 人
  模型模式 : mock（不产生真实 AI 费用，embedding 1024 维）

  体验建议（mock 语义检索已含重建后的向量，可直接命中）：
  1. 检索  POST /api/knowledge-bases/19/search  {"question":"什么是 Prompt Engineering","topK":3}
  2. 问答  POST /api/knowledge-bases/19/chat    {"question":"MCP 协议是什么"}
  3. 工作流 POST /api/workflows/11/run          （自动运维日报）
  4. 界面  浏览器打开 http://localhost:$Port    （若前端已构建）

  其他：
  - 历史库若向量与当前 mock 算法不匹配（检索相似度骤降/命中 0），加 -RebuildVectors 启动重建
  - 查看应用日志：$LOG_DIR\demo.out.log
"@
Write-Host '停止应用：Ctrl+C 或在任务管理器结束对应 java 进程（或运行 Stop-Process -Id <PID>）'
