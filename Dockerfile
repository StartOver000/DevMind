FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY .mvn/settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
# MCP stdio 服务器依赖 Node/npx（如 @modelcontextprotocol/server-everything）。
# 缺失时 MCP stdio 服务器无法启动（Cannot run program "npx"），每次启动报错并等超时。
RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs npm \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/devmind-0.1.0-SNAPSHOT.jar app.jar
# 容器安全加固（多角色审视-安全/SRE）：应用进程以非 root 运行（最小权限原则）。
# 方案：启动期以 root 初始化挂载点权限（兼容 bind mount），随后经 docker-entrypoint.sh
# 降权为 devmind 用户执行 java。生产建议改用命名卷（可进一步去除 root 初始化阶段）。
RUN useradd -r -u 10001 -m -s /bin/sh devmind \
    && mkdir -p /app/data /app/logs \
    && chown -R devmind:devmind /app
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["docker-entrypoint.sh"]
