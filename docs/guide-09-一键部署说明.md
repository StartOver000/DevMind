# DevMind 一键部署说明

## 方式一：Docker Compose 一键启动

```powershell
cd D:\WorkSpace\DevMind
.\scripts\start.ps1
```

或 Linux / macOS：

```bash
cd D:/WorkSpace/DevMind
chmod +x scripts/start.sh
./scripts/start.sh
```

首次启动会自动：

1. 生成 `.env`；
2. 构建后端镜像；
3. 启动 PostgreSQL + pgvector；
4. 启动 DevMind 后端；
5. 打开 `http://localhost:8080`。

如果 Docker Hub 暂时无法访问，Windows 启动脚本会自动降级：PostgreSQL 仍由 Docker 启动，DevMind 后端改用本机 JDK 运行。日志位于 `logs/devmind.out.log` 和 `logs/devmind.err.log`，网络恢复后可再次执行脚本切回容器模式。

默认使用 mock 模型，不需要 API Key。

如果 Docker Hub 拉取基础镜像超时，项目已配置阿里云 Maven 镜像；基础镜像可先用国内镜像源拉取并打标签：

```powershell
docker pull docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17
docker pull docker.m.daocloud.io/library/eclipse-temurin:17-jre
docker tag docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 maven:3.9-eclipse-temurin-17
docker tag docker.m.daocloud.io/library/eclipse-temurin:17-jre eclipse-temurin:17-jre
```

## 配置真实模型

编辑 `.env`：

```text
OPENAI_API_KEY=你的 Key
OPENAI_BASE_URL=https://api.openai.com
OPENAI_CHAT_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
DEVMIND_EMBEDDING_DIMENSIONS=1536
DEVMIND_MODEL_MODE=openai
```

然后重启：

```powershell
docker compose up -d --build
```

## 配置 SQL 诊断测试库

```text
DEVMIND_SQL_DIAGNOSIS_MODE=jdbc
DEVMIND_SQL_JDBC_URL=jdbc:mysql://localhost:3306/test
DEVMIND_SQL_USERNAME=root
DEVMIND_SQL_PASSWORD=xxx
```

## 常用命令

```powershell
docker compose ps
docker compose logs -f app
docker compose down
docker compose down -v
```

## CI/CD

GitHub Actions 已在 `.github/workflows/ci.yml` 配置：

- 每次 push / PR 自动跑 `mvn test`；
- 测试通过后执行 `mvn package`。
