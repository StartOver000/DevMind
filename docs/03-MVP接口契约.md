# DevMind MVP 接口契约

## 1. 通用约定

- Base URL：`/api`
- Content-Type：`application/json`
- 文件上传使用 `multipart/form-data`
- 所有时间使用 ISO-8601 格式
- 成功响应返回业务数据
- 失败响应返回统一错误结构

统一错误响应：

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "文档不存在",
  "traceId": "optional-trace-id",
  "timestamp": "2026-07-31T12:00:00Z"
}
```

## 2. 创建知识库

```http
POST /api/knowledge-bases
Content-Type: application/json
```

请求：

```json
{
  "name": "Java 技术知识库",
  "description": "Java、Spring、MySQL 相关资料"
}
```

响应：

```json
{
  "id": 1,
  "name": "Java 技术知识库",
  "description": "Java、Spring、MySQL 相关资料",
  "status": "ENABLED",
  "createdTime": "2026-07-31T12:00:00Z"
}
```

## 3. 查询知识库列表

```http
GET /api/knowledge-bases
```

响应：

```json
{
  "items": [
    {
      "id": 1,
      "name": "Java 技术知识库",
      "status": "ENABLED",
      "documentCount": 12
    }
  ]
}
```

## 4. 删除知识库

```http
DELETE /api/knowledge-bases/{knowledgeBaseId}
```

MVP 建议采用逻辑删除或禁用，而不是直接物理删除：

```json
{
  "id": 1,
  "status": "DISABLED"
}
```

行为约定：

- 已禁用的知识库不能上传文档或发起问答；
- 重复删除必须幂等；
- 文档和文本块默认保留，便于后续恢复或审计；
- 如果后续增加物理清理，必须作为独立的管理员操作。

## 5. 上传文档

```http
POST /api/knowledge-bases/{knowledgeBaseId}/documents
Content-Type: multipart/form-data
```

表单字段：

```text
file: Markdown 或 PDF 文件
```

响应：

```json
{
  "id": 10,
  "knowledgeBaseId": 1,
  "fileName": "MySQL索引专题.md",
  "status": "PROCESSING",
  "duplicate": false
}
```

行为约定：

- 文件类型只允许 `md`、`markdown`、`pdf`；
- 默认限制文件大小，具体值从配置读取；
- 计算 SHA-256 内容哈希；
- 同一知识库已有相同哈希时返回已有文档，并将 `duplicate` 设置为 `true`；
- MVP 可以同步处理，但接口结构必须保留状态字段，为后续异步化做准备。

## 6. 查询文档列表

```http
GET /api/knowledge-bases/{knowledgeBaseId}/documents?status=COMPLETED&page=1&pageSize=20
```

响应：

```json
{
  "items": [
    {
      "id": 10,
      "fileName": "MySQL索引专题.md",
      "fileType": "markdown",
      "status": "COMPLETED",
      "chunkCount": 36,
      "createdTime": "2026-07-31T12:00:00Z"
    }
  ],
  "page": 1,
  "pageSize": 20,
  "total": 1
}
```

## 7. 查询文档详情

```http
GET /api/documents/{documentId}
```

需要返回：

- 文件基本信息；
- 处理状态；
- 文本块数量；
- 失败原因；
- 创建和更新时间。

## 8. 删除文档

```http
DELETE /api/documents/{documentId}
```

响应：

```json
{
  "id": 10,
  "status": "DELETED"
}
```

删除操作需要幂等：文档已经是 DELETED 时再次删除不应报系统异常。

## 9. RAG 问答

```http
POST /api/knowledge-bases/{knowledgeBaseId}/chat
Content-Type: application/json
```

请求：

```json
{
  "question": "MySQL 深分页为什么会变慢？",
  "topK": 5,
  "conversationId": null
}
```

参数要求：

- `question` 非空，长度限制从配置读取；
- `topK` 缺省为 5，并设置最大值，防止一次检索过多上下文；
- `conversationId` 为空时创建新会话；
- 知识库不存在或已禁用时返回明确业务错误。

响应：

```json
{
  "conversationId": 100,
  "answer": "深分页通常使用 LIMIT OFFSET，随着 offset 增大，数据库需要扫描并丢弃更多前置记录，因此耗时会增加。可以考虑游标分页或延迟关联。",
  "references": [
    {
      "documentId": 10,
      "documentName": "MySQL索引专题.md",
      "chunkId": 300,
      "content": "......",
      "similarityScore": 0.86,
      "metadata": {
        "heading": "深分页原理"
      }
    }
  ]
}
```

无有效检索结果时：

- 可以返回“知识库中没有找到足够相关内容”；
- 不应把无关文本强行拼入 Prompt；
- 模型回答必须明确不确定性。

## 10. 查询会话消息

```http
GET /api/conversations/{conversationId}/messages
```

响应：

```json
{
  "conversationId": 100,
  "messages": [
    {
      "role": "user",
      "content": "MySQL 深分页为什么会变慢？",
      "createdTime": "2026-07-31T12:00:00Z"
    },
    {
      "role": "assistant",
      "content": "......",
      "createdTime": "2026-07-31T12:00:03Z"
    }
  ]
}
```

## 11. MVP 错误码

| 错误码 | 含义 |
| --- | --- |
| INVALID_ARGUMENT | 请求参数错误 |
| FILE_TYPE_NOT_SUPPORTED | 文件类型不支持 |
| FILE_TOO_LARGE | 文件超过大小限制 |
| KNOWLEDGE_BASE_NOT_FOUND | 知识库不存在 |
| DOCUMENT_NOT_FOUND | 文档不存在 |
| DOCUMENT_DUPLICATE | 文档重复，可按业务需要作为提示而非异常 |
| DOCUMENT_PROCESS_FAILED | 文档处理失败 |
| MODEL_CALL_FAILED | 模型调用失败 |
| VECTOR_SEARCH_FAILED | 向量检索失败 |
| INTERNAL_ERROR | 未知系统异常 |

## 12. 接口验收顺序

```text
创建知识库
  -> 上传 Markdown
  -> 查询文档状态
  -> 查询文档列表
  -> 发起问答
  -> 查看引用来源
  -> 删除文档
  -> 重复上传同一文档
```
