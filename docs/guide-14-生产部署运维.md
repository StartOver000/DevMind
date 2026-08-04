# DevMind 生产部署与运维

## 一键启动

```powershell
cd D:\WorkSpace\DevMind
.\scripts\start.ps1
```

## Nginx 反向代理

项目提供 `nginx/nginx.conf`，通过 profile 启动：

```powershell
docker compose --profile nginx up -d
```

访问 `http://localhost` 即可进入页面。

## HTTPS

1. 将证书放入 `nginx/ssl/server.crt` 和 `nginx/ssl/server.key`；
2. 在 `nginx/nginx.conf` 取消 HTTPS 配置的注释；
3. 重启 nginx：

```powershell
docker compose --profile nginx restart nginx
```

## 数据库备份

```powershell
.\scripts\backup.ps1
```

备份文件保存在 `backups/`，文件名带时间戳。

## 数据库恢复

```powershell
.\scripts\restore.ps1 -File backups\devmind_20260804_120000.dump
```

恢复会先清理再导入，操作前请确认目标环境。

## 日志

- 控制台日志实时输出；
- 文件日志写到 `logs/devmind.log`；
- 按天切割，保留 7 天；
- 日志包含 traceId。

## 自动重启

Docker Compose 中的服务都配置了 `restart: unless-stopped`，异常退出后会自动拉起。

## 常用运维命令

```powershell
docker compose ps
docker compose logs -f app
docker compose --profile nginx logs -f nginx
docker compose restart app
docker compose down
```
