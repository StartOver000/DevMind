param(
    [Parameter(Mandatory = $true)]
    [string]$File
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path $File)) {
    throw "备份文件不存在: $File"
}
docker cp $File devmind-postgres:/tmp/restore.dump
docker exec devmind-postgres pg_restore -U devmind -d devmind --clean --if-exists /tmp/restore.dump
Write-Host 'restore completed'
