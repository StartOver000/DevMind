# DevMind 接口使用手册

## 通用说明

- 接口前缀：`/api`；
- 普通请求使用 `application/json`；
- 上传文件使用 `multipart/form-data`；
- 时间使用 ISO-8601 格式；
- 业务接口通过请求头 `X-User-Id` 识别当前用户，缺省为 `1`（演示用户）；
- 成功直接返回业务数据；
- 失败返回统一错误结构。

统一错误示例：

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "文档不存在",
  "traceId": "17f3556d1a254c56",
  "timestamp": "2026-08-04T07:34:30Z"
}
```

## 接口总览

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | `/api/users` | 创建用户 |
| GET | `/api/users` | 查询用户列表 |
| POST | `/api/knowledge-bases` | 创建知识库 |
| GET | `/api/knowledge-bases` | 查询知识库列表 |
| DELETE | `/api/knowledge-bases/{id}` | 禁用知识库 |
| POST | `/api/knowledge-bases/{kbId}/documents` | 上传文档 |
| GET | `/api/knowledge-bases/{kbId}/documents` | 查询文档列表 |
| GET | `/api/documents/{id}` | 查询文档详情 |
| GET | `/api/tasks/{taskId}` | 查询处理任务 |
| GET | `/api/documents/{id}/task` | 按文档查询处理任务 |
| DELETE | `/api/documents/{id}` | 删除文档 |
| POST | `/api/knowledge-bases/{kbId}/chat` | RAG 问答 |
| GET | `/api/conversations/{id}/messages` | 查询会话消息 |
| GET | `/api/audit-logs` | 查询审计日志 |
| POST | `/api/knowledge-bases/{id}/members` | 添加知识库成员 |
| GET | `/api/knowledge-bases/{id}/members` | 查询知识库成员 |
| DELETE | `/api/knowledge-bases/{id}/members/{userId}` | 移除知识库成员 |
| POST | `/api/sql-diagnosis` | SQL 执行计划诊断 |
| GET | `/api/sql-diagnosis` | 查询诊断记录 |
| GET | `/api/sql-diagnosis/{id}` | 查询单条诊断记录 |
| POST | `/api/documents/{id}/versions` | 更新文档并保留旧版本 |
| GET | `/api/documents/{id}/versions` | 查询文档版本历史 |
| POST | `/api/documents/{id}/rollback/{version}` | 回滚文档版本 |
| GET | `/api/model-usage` | 查询模型用量 |
| GET | `/api/model-usage/summary` | 查询模型用量汇总 |
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/logout` | 退出登录 |
| POST | `/api/performance/retrieval` | 检索性能基准测试 |
| GET | `/actuator/metrics/devmind.http.requests` | 请求耗时指标 |

## 1. 创建知识库

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases" -Method Post -ContentType "application/json" -Body '{"name":"Java 技术知识库","description":"Java、Spring、MySQL 相关资料"}'
```

返回：

```json
{
  "id": 1,
  "name": "Java 技术知识库",
  "description": "Java、Spring、MySQL 相关资料",
  "status": "ENABLED",
  "ownerId": 1,
  "createdTime": "2026-08-04T07:35:12Z"
}
```

## 2. 创建用户

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/users" -Method Post -ContentType "application/json" -Body '{"username":"alice","displayName":"Alice"}'
```

返回：

```json
{
  "id": 2,
  "username": "alice",
  "displayName": "Alice",
  "createdTime": "2026-08-04T07:56:24Z"
}
```

## 3. 查询知识库列表

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases"
```

返回：

```json
{
  "items": [
    {
      "id": 1,
      "name": "Java 技术知识库",
      "status": "ENABLED",
      "documentCount": 1
    }
  ]
}
```

## 4. 上传文档

```powershell
$form = @{ file = Get-Item "D:\WorkSpace\DevMind\examples\MySQL索引专题.md" }
Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases/1/documents" -Method Post -Form $form
```

返回：

```json
{
  "id": 1,
  "knowledgeBaseId": 1,
  "fileName": "MySQL索引专题.md",
  "status": "UPLOADED",
  "duplicate": false,
  "taskId": 1
}
```

上传接口会立刻返回，文档处理在后台进行。同一知识库再次上传相同内容时，返回 `"duplicate": true`。

## 5. 查询处理任务

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/1/task"
```

返回：

```json
{
  "taskId": 1,
  "documentId": 1,
  "status": "SUCCEEDED",
  "retryCount": 0,
  "maxRetries": 3,
  "errorMessage": null,
  "createdTime": "2026-08-04T07:43:08Z",
  "updatedTime": "2026-08-04T07:43:08Z"
}
```

任务状态：`PENDING` 等待处理、`PROCESSING` 处理中、`SUCCEEDED` 成功、`FAILED` 失败。

## 6. 查询文档列表

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases/1/documents?status=COMPLETED&page=1&pageSize=20"
```

返回：

```json
{
  "items": [
    {
      "id": 1,
      "fileName": "MySQL索引专题.md",
      "fileType": "markdown",
      "status": "COMPLETED",
      "chunkCount": 4,
      "createdTime": "2026-08-04T07:35:12Z"
    }
  ],
  "page": 1,
  "pageSize": 20,
  "total": 1
}
```

## 7. 查询文档详情

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/1"
```

可以看到文件大小、内容哈希、处理状态、错误原因和文本块数量。

## 8. RAG 问答

```powershell
$body = @{ question = "MySQL 深分页为什么会变慢？"; topK = 5; conversationId = $null } | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases/1/chat" -Method Post -ContentType "application/json" -Body $body
```

返回：

```json
{
  "conversationId": 1,
  "answer": "回答内容……",
  "references": [
    {
      "documentId": 1,
      "documentName": "MySQL索引专题.md",
      "chunkId": 2,
      "content": "相关文本块……",
      "similarityScore": 0.8887,
      "metadata": {
        "heading": "深分页为什么慢"
      }
    }
  ]
}
```

`references` 就是引用来源。

## 9. 查询会话消息

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/conversations/1/messages"
```

返回：

```json
{
  "conversationId": 1,
  "messages": [
    {
      "role": "user",
      "content": "MySQL 深分页为什么会变慢？",
      "createdTime": "2026-08-04T07:35:12Z"
    },
    {
      "role": "assistant",
      "content": "回答内容……",
      "createdTime": "2026-08-04T07:35:12Z"
    }
  ]
}
```

## 10. 删除文档

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/1" -Method Delete
```

返回：

```json
{
  "id": 1,
  "status": "DELETED"
}
```

## 11. 禁用知识库

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/knowledge-bases/1" -Method Delete
```

返回：

```json
{
  "id": 1,
  "status": "DISABLED"
}
```

## 12. 查询审计日志

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/audit-logs?userId=1&limit=20"
```

返回：

```json
{
  "items": [
    {
      "id": 1,
      "userId": 1,
      "action": "CREATE_KNOWLEDGE_BASE",
      "targetType": "knowledge_base",
      "targetId": 1,
      "detail": "用户1知识库",
      "createdTime": "2026-08-04T07:56:24Z"
    }
  ]
}
```

常见操作：`CREATE_KNOWLEDGE_BASE`、`DELETE_KNOWLEDGE_BASE`、`UPLOAD_DOCUMENT`、`DELETE_DOCUMENT`、`CHAT`。

## 13. SQL 执行计划诊断

```powershell
$body = @{
  sql = "SELECT * FROM orders ORDER BY created_time LIMIT 100000, 20"
  dataSource = "mysql"
  knowledgeBaseId = $null
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/sql-diagnosis" -Method Post -Headers @{'X-User-Id'='1'} -ContentType "application/json" -Body $body
```

返回：

```json
{
  "id": 1,
  "sql": "SELECT * FROM orders ORDER BY created_time LIMIT 100000, 20",
  "dataSource": "mysql",
  "riskLevel": "HIGH",
  "risks": [
    {
      "rule": "DEEP_PAGINATION",
      "level": "HIGH",
      "message": "深分页会导致越翻越慢"
    }
  ],
  "plan": [
    {
      "table": "orders",
      "type": "ALL",
      "key": null,
      "rows": "1000000",
      "extra": "Using filesort"
    }
  ],
  "advice": "诊断建议……"
}
```

默认使用内置模拟执行计划，不需要真实 MySQL。要连接测试库，设置：

```powershell
$env:DEVMIND_SQL_DIAGNOSIS_MODE="jdbc"
$env:DEVMIND_SQL_JDBC_URL="jdbc:mysql://localhost:3306/test"
$env:DEVMIND_SQL_USERNAME="root"
$env:DEVMIND_SQL_PASSWORD="xxx"
```

红线：只允许诊断 `SELECT` / `EXPLAIN`，`UPDATE`、`DELETE`、`DROP` 等会被拒绝；AI 只给建议，不会自动执行修改语句。

## 错误码

| 错误码 | 含义 |
| --- | --- |
| INVALID_ARGUMENT | 参数错误 |
| FILE_TYPE_NOT_SUPPORTED | 文件类型不支持 |
| FILE_TOO_LARGE | 文件太大 |
| KNOWLEDGE_BASE_NOT_FOUND | 知识库不存在或已禁用 |
| DOCUMENT_NOT_FOUND | 文档不存在 |
| CONVERSATION_NOT_FOUND | 会话不存在 |
| TASK_NOT_FOUND | 任务不存在 |
| USER_NOT_FOUND | 用户不存在 |
| FORBIDDEN | 无权访问 |
| SQL_DIAGNOSIS_NOT_FOUND | 诊断记录不存在 |
| DOCUMENT_PROCESS_FAILED | 文档处理失败 |
| MODEL_CALL_FAILED | 模型调用失败 |
| VECTOR_SEARCH_FAILED | 向量检索失败 |
| INTERNAL_ERROR | 系统内部错误 |
