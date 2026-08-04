$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path '.env')) {
    Copy-Item '.env.example' '.env'
    Write-Host '已生成 .env，可修改模型配置'
}

Write-Host '阶段 1/3：使用本机 Maven 构建应用（不会在 Docker 内重复下载依赖）'
mvn -q -DskipTests package

function Invoke-WithRetry([scriptblock]$Action, [string]$Name, [int]$Attempts = 3) {
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            & $Action
            return
        } catch {
            if ($attempt -eq $Attempts) { throw }
            Write-Warning "$Name 失败，第 $attempt/$Attempts 次，10 秒后重试：$($_.Exception.Message)"
            Start-Sleep -Seconds 10
        }
    }
}

Write-Host '阶段 2/3：构建应用运行时镜像'
try {
    Invoke-WithRetry { docker compose build app } 'Docker 镜像构建'
    Write-Host '阶段 3/3：启动 PostgreSQL 和 DevMind 容器'
    Invoke-WithRetry { docker compose up -d postgres app } 'Docker Compose 启动'
    Write-Host 'DevMind 启动中：http://localhost:8080'
} catch {
    Write-Warning 'Docker 运行时镜像暂时无法拉取，切换为：PostgreSQL 使用 Docker，DevMind 使用本机 JDK 启动。'
    Invoke-WithRetry { docker compose up -d postgres } 'PostgreSQL 启动'

    Get-Content '.env' | Where-Object { $_ -match '^\s*[^#][^=]*=' } | ForEach-Object {
        $name, $value = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
    }
    if (Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq (Get-Command java).Source }) {
        Write-Host '检测到 Java 进程，未重复启动应用。'
    } else {
        New-Item -ItemType Directory -Force '.\logs' | Out-Null
        Start-Process java -ArgumentList '-jar', 'target/devmind-0.1.0-SNAPSHOT.jar' -WorkingDirectory (Get-Location) -RedirectStandardOutput '.\logs\devmind.out.log' -RedirectStandardError '.\logs\devmind.err.log'
    }
    Write-Host '已切换本机启动：http://localhost:8080'
}
