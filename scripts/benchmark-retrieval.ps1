param(
    [int]$Iterations = 10,
    [int]$Concurrency = 10,
    [switch]$SkipConcurrency
)
# 检索性能基线回归（P2-2）：一键复测热路径延迟并与基线对比，超阈值提示劣化。
# 用法: powershell -File scripts/benchmark-retrieval.ps1  （需 devmind-app 运行在 8080）
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'

function Invoke-Benchmark([int]$kb, [string]$q, [int]$n) {
    $body = @{ knowledgeBaseId = $kb; question = $q; iterations = $n } | ConvertTo-Json
    Invoke-RestMethod -Uri "$base/api/performance/retrieval" -Method POST `
        -ContentType 'application/json' -Headers @{ 'X-User-Id' = '1' } -Body $body
}

# 基线（docs/product/性能基线-20260815.md 2026-08-15）：KB20 热路径 ≤120ms
$cases = @(
    @{ kb = 20; q = 'Java 并发编程中 synchronized 和 ReentrantLock 的区别是什么？'; label = 'KB20 热路径'; baseline = 120.0 }
    @{ kb = 19; q = '什么是 Transformer 的注意力机制？它解决了什么痛点？'; label = 'KB19 热路径'; baseline = 80.0 }
)

Write-Host "== 检索性能基线回归（$Iterations 次迭代均值）=="
foreach ($c in $cases) {
    # 预热：先跑 1 次命中 embedding 缓存，再测均值
    Invoke-Benchmark $c.kb $c.q 1 | Out-Null
    $r = Invoke-Benchmark $c.kb $c.q $Iterations
    $avg = [math]::Round($r.avgMs, 1)
    $bl = $c.baseline
    $flag = if ($avg -gt $bl * 3) { '!! 劣化(>3x 基线)' } elseif ($avg -gt $bl) { '注意(>基线)' } else { 'OK' }
    Write-Host ("  {0}: avg={1}ms 基线≤{2}ms [{3}]" -f $c.label, $avg, $bl, $flag)
}

if (-not $SkipConcurrency) {
    Write-Host "== 并发热路径（$Concurrency 并发 x3 轮）=="
    $c = $cases[0]
    $bodyJson = @{ knowledgeBaseId = $c.kb; question = $c.q; iterations = 1 } | ConvertTo-Json -Compress
    # RunspacePool：同进程线程池并发（Start-Job 有进程启动开销、Task.Run 缺 runspace，均不可用）
    $pool = [runspacefactory]::CreateRunspacePool(1, $Concurrency)
    $pool.Open()
    $jobs = @()
    for ($r = 0; $r -lt 3; $r++) {
        for ($i = 0; $i -lt $Concurrency; $i++) {
            $ps = [powershell]::Create()
            $ps.RunspacePool = $pool
            [void]$ps.AddScript({
                param($uri, $b)
                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                Invoke-RestMethod -Uri $uri -Method POST -ContentType 'application/json' -Headers @{ 'X-User-Id' = '1' } -Body $b | Out-Null
                $sw.Stop()
                $sw.Elapsed.TotalMilliseconds
            }).AddArgument("$base/api/performance/retrieval").AddArgument($bodyJson)
            $jobs += @{ ps = $ps; async = $ps.BeginInvoke() }
        }
    }
    $arr = @()
    foreach ($j in $jobs) {
        $arr += $j.ps.EndInvoke($j.async)
        $j.ps.Dispose()
    }
    $pool.Close()
    $arr = @($arr | Sort-Object)
    $p50 = $arr[[int]($arr.Count * 0.5)]
    $p95 = $arr[[int]($arr.Count * 0.95)]
    Write-Host ("  请求数={0} p50={1:N1}ms p95={2:N1}ms" -f $arr.Count, $p50, $p95)
}
Write-Host '== 完成 =='
