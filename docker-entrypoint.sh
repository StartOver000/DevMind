#!/bin/sh
set -e

# 容器入口：启动期以 root 初始化挂载点权限（兼容 Windows Docker Desktop bind mount），
# 随后降权为 devmind 用户运行应用（最小权限原则，应用进程非 root）。
# 生产（Linux 宿主）建议改用命名卷并直接 USER devmind，本脚本在命名卷下也无害。
mkdir -p /app/logs /app/data
chown -R devmind:devmind /app/logs /app/data 2>/dev/null || true
chmod -R u+rwX /app/logs /app/data 2>/dev/null || true
# 兼容 bind mount：若挂载点 owner 非 devmind，显式放行写权限
chmod 777 /app/logs /app/data 2>/dev/null || true

# 降权运行应用（su 从 root 切普通用户无需密码）
# 注意：su 会重置 PATH，java 需用绝对路径（temurin JRE 位于 /opt/java/openjdk/bin）
exec su devmind -s /bin/sh -c "cd /app && exec /opt/java/openjdk/bin/java -jar app.jar"
