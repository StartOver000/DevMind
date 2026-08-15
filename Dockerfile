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
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
