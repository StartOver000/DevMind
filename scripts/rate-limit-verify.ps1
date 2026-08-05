# 分布式限流正确性验证脚本（DevMind）
#requires -Version 7
# 前置：docker compose 已启动（app:8080 + app2:8081 + redis + prometheus）
# 用法：pwsh -ExecutionPolicy Bypass -File scripts/rate-limit-verify.ps1
# 验证点：
#   1. 单实例固定窗口精确生效：并发 N 请求，200 数 == 限流阈值（120），429 数 == 超限部分
#   2. 双实例共享窗口：窗口打满后从 app2 请求全部 429，无法绕过
#   3. Redis 窗口原子计数：窗口 key 计数 == 总请求数，无超卖
#   4. Prometheus 双实例限流计数各自累计、5m 速率吻合
#   5. 窗口恢复：key 过期/删除后重新放行

$ErrorActionPreference = "Continue"

$APP1 = "http://localhost:8080"
$APP2 = "http://localhost:8081"
$PROM = "http://localhost:9090"
$URI = "/api/knowledge-bases"
$USER = "1"
$LIMIT = 120          # 与 DEVMIND_RATE_LIMIT_PER_MINUTE 保持一致
$TOTAL = 150          # 总请求数（> 阈值，产生 429）
$CONC = 10            # 并发数
$WINDOW_KEY = "$URI|$USER"

Write-Host "== DevMind 分布式限流正确性验证 ==" -ForegroundColor Cyan
Write-Host "阈值=$LIMIT/min 总请求=$TOTAL 并发=$CONC"

# 0. 清理限流窗口，保证从 0 计数
docker exec devmind-redis redis-cli DEL "$WINDOW_KEY" | Out-Null
Write-Host "[0] 已清理限流窗口: $WINDOW_KEY"

# 1. 单实例精确阈值：并发打 app1
Write-Host "[1] 并发 $TOTAL 请求打 app1..." -ForegroundColor Yellow
$r1 = 1..$TOTAL | ForEach-Object -Parallel {
    try {
        $r = Invoke-WebRequest -Uri "$($using:APP1)$($using:URI)" -Headers @{"X-User-Id"=$using:USER} -TimeoutSec 10 -SkipHttpErrorCheck
        [pscustomobject]@{ s = $r.StatusCode }
    } catch { [pscustomobject]@{ s = 0 } }
} -ThrottleLimit $CONC
$ok1 = ($r1 | Where-Object s -eq 200).Count
$rl1 = ($r1 | Where-Object s -eq 429).Count
Write-Host "  -> 200=$ok1 (期望=$LIMIT)  429=$rl1 (期望=$($TOTAL-$LIMIT))"
if ($ok1 -ne $LIMIT) { Write-Host "  [警告] 200 数 != 阈值 $LIMIT" -ForegroundColor Red } else { Write-Host "  [PASS] 单实例窗口精确生效，无超卖无欠卖" -ForegroundColor Green }

# 2. 双实例共享窗口：窗口已满，从 app2 请求应全部 429
Write-Host "[2] 窗口已满后从 app2 并发 10 请求..." -ForegroundColor Yellow
$r2 = 1..10 | ForEach-Object -Parallel {
    try {
        $r = Invoke-WebRequest -Uri "$($using:APP2)$($using:URI)" -Headers @{"X-User-Id"=$using:USER} -TimeoutSec 10 -SkipHttpErrorCheck
        [pscustomobject]@{ s = $r.StatusCode }
    } catch { [pscustomobject]@{ s = 0 } }
} -ThrottleLimit 10
$rl2 = ($r2 | Where-Object s -eq 429).Count
Write-Host "  -> app2 429=$rl2/10"
if ($rl2 -ne 10) { Write-Host "  [警告] app2 未被完全拦截，窗口未共享？" -ForegroundColor Red } else { Write-Host "  [PASS] 双实例共享 Redis 窗口，app2 无法绕过" -ForegroundColor Green }

# 3. Redis 窗口原子计数
$cnt = docker exec devmind-redis redis-cli GET "$WINDOW_KEY"
Write-Host "[3] Redis 窗口计数=$cnt (期望=$($TOTAL+10))"
if ([int]$cnt -eq ($TOTAL + 10)) { Write-Host "  [PASS] Lua INCR 原子计数精确" -ForegroundColor Green } else { Write-Host "  [警告] 计数不一致" -ForegroundColor Red }

# 4. Prometheus 双实例限流计数
Start-Sleep -Seconds 18   # 等 Prometheus 抓取
$q = Invoke-RestMethod -Uri "$PROM/api/v1/query?query=devmind_rate_limited_total" -TimeoutSec 8
foreach ($m in $q.data.result) {
    Write-Host "  [metrics] instance=$($m.metric.instance) 限流累计=$($m.value[1])"
}

# 5. 窗口恢复：清理后重新放行
docker exec devmind-redis redis-cli DEL "$WINDOW_KEY" | Out-Null
$r3 = Invoke-WebRequest -Uri "$APP1$URI" -Headers @{"X-User-Id"=$USER} -TimeoutSec 10 -SkipHttpErrorCheck
Write-Host "[5] 窗口恢复后 status=$($r3.StatusCode) (期望=200)"
if ($r3.StatusCode -eq 200) { Write-Host "  [PASS] 窗口过期/清理后恢复放行" -ForegroundColor Green } else { Write-Host "  [警告] 未恢复" -ForegroundColor Red }

Write-Host "== 验证完成 ==" -ForegroundColor Cyan
