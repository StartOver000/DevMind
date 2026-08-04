# 如何运行 DevMind

## 电脑需要装什么

- JDK 17 或 21；
- Maven 3.9+；
- Docker Desktop；
- 可选：一个大模型 API Key。

本项目已经在你电脑上验证过：JDK 21、Maven 3.9.14、Docker 29.4。

## 方式一：没有模型 Key，先看效果（推荐）

这种模式使用项目内置的“模拟模型”，不需要花钱，也不需要联网调用大模型，用来体验完整流程。

### 1. 启动数据库

先打开 Docker Desktop，等它运行起来，然后执行：

```powershell
cd D:\WorkSpace\DevMind
docker compose up -d
```

### 2. 启动后端

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=mock"
```

看到类似 `Started DevMindApplication` 就说明启动成功。

### 3. 检查是否正常

浏览器打开：

```text
http://localhost:8080/actuator/health
```

看到：

```json
{"status":"UP"}
```

就说明服务和数据库都正常。

## 方式二：接入真实大模型

在 PowerShell 里设置环境变量，然后重新启动：

```powershell
$env:OPENAI_API_KEY="你的 Key"
$env:OPENAI_BASE_URL="https://api.openai.com"
$env:OPENAI_CHAT_MODEL="gpt-4o-mini"
$env:OPENAI_EMBEDDING_MODEL="text-embedding-3-small"
$env:DEVMIND_EMBEDDING_DIMENSIONS="1536"

mvn spring-boot:run
```

如果使用 DeepSeek、通义千问、智谱或 Ollama，只需要把 `OPENAI_BASE_URL` 改成对应地址，并同步调整模型名称和向量维度。

注意：

- API Key 不要写进代码；
- 向量维度必须和 Embedding 模型一致；
- 切换模型后，旧向量不能直接复用。

## 快速体验一条完整流程

服务启动后，在另一个 PowerShell 窗口执行：

```powershell
$kb = Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases" -Method Post -ContentType "application/json" -Body '{"name":"测试知识库"}'

$form = @{ file = Get-Item "D:\WorkSpace\DevMind\examples\MySQL索引专题.md" }
$doc = Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases/$($kb.id)/documents" -Method Post -Form $form

# 上传接口会立刻返回 UPLOADED 和 taskId，后台自动处理
Start-Sleep -Seconds 2
$task = Invoke-RestMethod -Uri "http://localhost:8080/api/documents/$($doc.id)/task" -Method Get
$task | ConvertTo-Json -Depth 5

$body = @{ question = "MySQL 深分页为什么会变慢？" } | ConvertTo-Json
$chat = Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases/$($kb.id)/chat" -Method Post -ContentType "application/json" -Body $body
$chat | ConvertTo-Json -Depth 8
```

详细接口示例见 `docs/guide-03-接口使用手册.md`。

## 如何停止

停止后端：

- 如果是前台运行，按 `Ctrl+C`；
- 如果是后台运行，先看 `target\devmind.pid` 里的进程号，再执行：

```powershell
$id = Get-Content "D:\WorkSpace\DevMind\target\devmind.pid"
Stop-Process -Id $id -Force
```

停止数据库：

```powershell
cd D:\WorkSpace\DevMind
docker compose down
```

想连数据一起清空：

```powershell
docker compose down -v
```

## 常见问题

| 现象 | 原因 | 解决办法 |
| --- | --- | --- |
| 提示 Docker daemon 连不上 | Docker Desktop 没启动 | 先打开 Docker Desktop，等引擎 ready |
| 5432 端口被占用 | 本机已有 PostgreSQL | 修改 `docker-compose.yml` 端口，并同步改 `POSTGRES_PORT` |
| 8080 端口被占用 | 其他程序占用 | 启动时加 `--server.port=8081` |
| 上传后状态 FAILED | 解析、向量或模型调用失败 | 查看文档详情里的 `errorMessage` |
| 问答返回模型调用失败 | API Key 或模型地址不对 | 检查环境变量，确认网络和额度 |
| 向量维度不一致 | Embedding 模型和配置不一致 | 修改 `DEVMIND_EMBEDDING_DIMENSIONS` |
