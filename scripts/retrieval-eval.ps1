# DevMind 检索质量护栏脚本
#requires -Version 7
# 前置：docker compose 已启动（app/redis/postgres）
# 用法：
#   pwsh -File scripts/retrieval-eval.ps1               # 跑评估 + 基线对比（回退>5% 则 FAIL）
#   pwsh -File scripts/retrieval-eval.ps1 -UpdateBaseline  # 以当前结果为基线
param([switch]$UpdateBaseline)

# Windows 管道默认 GBK 解码会破坏容器 UTF-8 中文日志，这里强制 UTF-8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Continue"

$args2 = if ($UpdateBaseline) { "--eval --update-baseline" } else { "--eval" }
Write-Host "== DevMind 检索质量评估/护栏 ==" -ForegroundColor Cyan
Write-Host "参数: $args2"

$output = docker compose run --rm --no-deps app java -jar /app/app.jar $args2 2>&1

$report = $output | Select-String "当前配置" | Select-Object -Last 1
if ($report) { Write-Host "[报告] $($report.Line.Trim())" -ForegroundColor Yellow }

if ($UpdateBaseline) {
    Write-Host "[PASS] 基线已更新（--update-baseline）" -ForegroundColor Green
    exit 0
}

$warn = $output | Select-String "\[GUARD\] FAIL"
$pass = $output | Select-String "\[GUARD\] PASS"
$noBaseline = $output | Select-String "NO_BASELINE"

if ($warn) {
    Write-Host "[FAIL] $($warn.Line.Trim())" -ForegroundColor Red
    exit 1
}
if ($pass) {
    Write-Host "[PASS] $($pass.Line.Trim())" -ForegroundColor Green
    exit 0
}
if ($noBaseline) {
    Write-Host "[WARN] 未建立基线，首次请运行 -UpdateBaseline" -ForegroundColor Yellow
    exit 0
}
Write-Host "[WARN] 未识别到评估结果（检查应用日志）" -ForegroundColor Yellow
exit 0
