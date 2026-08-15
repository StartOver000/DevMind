$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

# 备份保留份数（环境变量可覆盖，默认 14）
$keep = [int]($env:BACKUP_KEEP ?? 14)
New-Item -ItemType Directory -Force -Path 'backups' | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$base = "devmind_$timestamp"

# 1) 数据库：pg_dump 自定义格式（schema + 数据）
docker exec devmind-postgres pg_dump -U devmind -d devmind -F c -f "/tmp/$base.dump"
docker cp "devmind-postgres:/tmp/$base.dump" "backups/$base.dump"
docker exec devmind-postgres rm -f "/tmp/$base.dump"
Write-Host "db backup: backups/$base.dump"

# 2) 文件数据：data/files（用户上传文档，pg_dump 不含）——打包保留目录结构
$filesDir = Join-Path (Get-Location) 'data/files'
if ((Test-Path $filesDir) -and (Get-ChildItem $filesDir -Recurse -Force | Measure-Object).Count -gt 0) {
    tar -czf "backups/$base.files.tar.gz" -C data files
    Write-Host "files backup: backups/$base.files.tar.gz"
}
else {
    Write-Host 'data/files 为空，跳过文件备份'
}

# 3) 保留策略：只保留最近 $keep 份（db 与 files 配套清理）
Get-ChildItem backups -Filter 'devmind_*.dump' | Sort-Object Name -Descending | Select-Object -Skip $keep | Remove-Item -Force
Get-ChildItem backups -Filter 'devmind_*.files.tar.gz' | Sort-Object Name -Descending | Select-Object -Skip $keep | Remove-Item -Force
Write-Host "backup done, keep latest $keep"
