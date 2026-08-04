$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force -Path 'backups' | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$name = "devmind_$timestamp.dump"
docker exec devmind-postgres pg_dump -U devmind -d devmind -F c -f "/tmp/$name"
docker cp "devmind-postgres:/tmp/$name" "backups/$name"
Write-Host "backup created: backups/$name"
