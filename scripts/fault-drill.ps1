# DevMind 故障注入测试与恢复演练脚本
# 用法：pwsh -File .\scripts\fault-drill.ps1
# 场景：重启策略验证 / 数据库中断自愈 / 登录接口可用性
# 注意：Docker Desktop (Windows) 下 docker kill 强制终止不触发 restart policy 自动重启（环境已知限制），
#       故本脚本验证 restart 策略配置 + 容器启动恢复 + 数据库中断后应用自愈（Hikari 自动重连）。
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'

function Test-DevMindHealth {
    try {
        $h = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 8
        return $h.status
    } catch {
        return 'DOWN'
    }
}

# 循环等待服务恢复（最多 120 秒）
function Wait-DevMindHealthy {
    param([int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Test-DevMindHealth) -eq 'UP') { return $true }
        Start-Sleep -Seconds 5
    }
    return (Test-DevMindHealth) -eq 'UP'
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " DevMind 故障演练" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

Write-Host "`n[0] 初始健康检查" -ForegroundColor Yellow
Write-Host ("  health = " + (Test-DevMindHealth))

Write-Host "`n[1] 演练：重启策略配置与容器恢复" -ForegroundColor Yellow
$policy = docker inspect devmind-app --format '{{.HostConfig.RestartPolicy.Name}}'
Write-Host ("  restart policy = " + $policy)
if ($policy -eq 'unless-stopped') { Write-Host "  [PASS] restart: unless-stopped 已生效（守护进程重启/进程崩溃时自动拉起）" -ForegroundColor Green } else { Write-Host "  [FAIL] restart 策略异常" -ForegroundColor Red }
Write-Host "  -> 模拟容器停止后手动拉起 (docker start)"
docker stop devmind-app | Out-Null
Start-Sleep -Seconds 3
docker start devmind-app | Out-Null
$ok = Wait-DevMindHealthy 60
$status = Test-DevMindHealth
Write-Host ("  恢复后 health = " + $status)
if ($ok) { Write-Host "  [PASS] 容器停止后可正常拉起并恢复服务" -ForegroundColor Green } else { Write-Host "  [FAIL] 容器无法恢复" -ForegroundColor Red }

Write-Host "`n[2] 演练：数据库短暂中断后应用自愈（Hikari 自动重连）" -ForegroundColor Yellow
Write-Host "  -> 停止 PostgreSQL"
docker stop devmind-postgres | Out-Null
Start-Sleep -Seconds 5
Write-Host ("  中断期间 health = " + (Test-DevMindHealth))
Write-Host "  -> 重启 PostgreSQL"
docker start devmind-postgres | Out-Null
$ok = Wait-DevMindHealthy 120
$status = Test-DevMindHealth
Write-Host ("  恢复后 health = " + $status)
if ($ok) { Write-Host "  [PASS] 数据库恢复后服务自愈，无需重启应用" -ForegroundColor Green } else { Write-Host "  [FAIL] 数据库恢复后服务未自愈" -ForegroundColor Red }

Write-Host "`n[3] 演练：登录接口可用性（安全加固后）" -ForegroundColor Yellow
try {
    $r = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body '{"username":"demo","password":"demo123"}' -TimeoutSec 8
    Write-Host ("  登录成功 userId=" + $r.userId)
    Write-Host "  [PASS] 登录接口正常" -ForegroundColor Green
} catch {
    Write-Host ("  登录失败: " + $_.Exception.Message)
    Write-Host "  [FAIL] 登录接口异常" -ForegroundColor Red
}

Write-Host "`n==================================================" -ForegroundColor Cyan
Write-Host " 演练完成" -ForegroundColor Cyan
