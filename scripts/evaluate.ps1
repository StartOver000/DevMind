# DevMind 检索评估自动化脚本
# 用法：pwsh -File .\scripts\evaluate.ps1 -KbId 3 -Mode heuristic
# 说明：登录演示账号 -> 运行检索评估 -> 输出主题命中率 -> 归档报告到 docs/eval-reports/
param(
    [int]$KbId = 3,
    [string]$Mode = "heuristic",
    [string]$User = "demo",
    [string]$Password = "demo123",
    [string]$Base = "http://localhost:8080"
)

$ErrorActionPreference = 'Stop'

Write-Host "=== 登录 $User ==="
$login = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/login" `
    -ContentType 'application/json' -Body ('{"username":"' + $User + '","password":"' + $Password + '"}') -TimeoutSec 10
$headers = @{ Authorization = "Bearer $($login.token)" }

Write-Host "=== 运行评估 kb=$KbId mode=$Mode ==="
$body = '{"knowledgeBaseId":' + $KbId + ',"rerankMode":"' + $Mode + '"}'
$result = Invoke-RestMethod -Method Post -Uri "$Base/api/evaluations/retrieval" `
    -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 600

Write-Host ("总计: " + $result.total + " 条, 命中: " + $result.hits + ", 命中率: " + [math]::Round($result.hitRate * 100, 1) + "%")

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$dir = Join-Path (Split-Path $PSScriptRoot -Parent) 'docs\eval-reports'
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$path = Join-Path $dir ("eval-kb$KbId-$Mode-$stamp.md")

"# 检索评估报告 kb=$KbId mode=$Mode" | Out-File $path -Encoding utf8
"" | Out-File $path -Append -Encoding utf8
"- 时间: $stamp" | Out-File $path -Append -Encoding utf8
"- 总计: $($result.total) 条, 命中: $($result.hits), 命中率: $([math]::Round($result.hitRate * 100, 1))%" | Out-File $path -Append -Encoding utf8
"" | Out-File $path -Append -Encoding utf8
"| 主题 | 条数 | 命中 | 命中率 |" | Out-File $path -Append -Encoding utf8
"| --- | --- | --- | --- |" | Out-File $path -Append -Encoding utf8
foreach ($topic in $result.topics) {
    "| $($topic.topic) | $($topic.total) | $($topic.hits) | $([math]::Round($topic.hitRate * 100, 1))% |" | Out-File $path -Append -Encoding utf8
}

Write-Host "报告已归档: $path" -ForegroundColor Green
