param(
    [Parameter(Mandatory = $true)]
    [string]$File,
    [string]$TargetDb = 'devmind'
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path $File)) {
    throw "备份文件不存在: $File"
}

# 1) 文件数据恢复（配套 .files.tar.gz 存在时，还原到 data/files 目录结构；
#    注意 -C data：备份用 tar -C data files 打包，恢复必须解压到 data/ 下）
$files = [System.IO.Path]::ChangeExtension($File, '.files.tar.gz')
if (Test-Path $files) {
    New-Item -ItemType Directory -Force -Path 'data' | Out-Null
    tar -xzf $files -C data
    Write-Host "files restored from $files"
}
else {
    Write-Host "未找到配套文件备份: $files（仅恢复数据库）"
}

# 2) 演练模式：目标库不是 devmind 时先建临时库（不触碰生产库）
if ($TargetDb -ne 'devmind') {
    docker exec devmind-postgres psql -U devmind -d postgres -c "DROP DATABASE IF EXISTS $TargetDb" | Out-Null
    docker exec devmind-postgres psql -U devmind -d postgres -c "CREATE DATABASE $TargetDb" | Out-Null
    Write-Host "演练目标库已创建: $TargetDb"
}

# 3) 恢复数据库
if ($TargetDb -eq 'devmind') {
    docker exec devmind-postgres psql -U devmind -d devmind -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;' | Out-Null
}
docker cp $File devmind-postgres:/tmp/restore.dump
docker exec devmind-postgres pg_restore -U devmind -d $TargetDb --clean --if-exists /tmp/restore.dump
docker exec devmind-postgres rm -f /tmp/restore.dump
Write-Host "restore completed -> $TargetDb"
