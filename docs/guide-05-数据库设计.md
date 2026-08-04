# DevMind 数据库设计

## 数据库

- PostgreSQL 16；
- pgvector 扩展；
- 启动时由 `DatabaseInitializer` 自动建表。

## 表关系

```text
app_user（用户）
  └── knowledge_base（知识库，created_by 指向用户）
        └── audit_log（审计日志，user_id 指向用户）
knowledge_base（知识库）
  └── document（文档）
        └── document_task（处理任务）
        └── document_chunk（文本块 + 向量）

chat_conversation（会话）
  └── chat_message（消息）
```

## 1. app_user 用户

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| username | varchar | 用户名，唯一 |
| display_name | varchar | 显示名称 |
| password_hash | varchar | 密码哈希 |
| created_time | timestamp | 创建时间 |

启动时自动创建演示用户 `demo`，ID 为 1。

## 2. knowledge_base 知识库

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| name | varchar | 知识库名称，唯一 |
| description | varchar | 描述 |
| status | varchar | ENABLED / DISABLED |
| created_by | bigint | 所属用户，作为权限隔离依据 |
| created_time | timestamp | 创建时间 |
| updated_time | timestamp | 更新时间 |

## 3. document 文档

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| knowledge_base_id | bigint | 所属知识库 |
| file_name | varchar | 原始文件名 |
| file_type | varchar | markdown / pdf |
| file_size | bigint | 文件大小 |
| file_path | varchar | 文件保存路径 |
| content_hash | varchar | 内容 SHA-256 |
| status | varchar | UPLOADED / PROCESSING / COMPLETED / FAILED / DELETED |
| error_message | varchar | 失败原因 |
| created_time | timestamp | 创建时间 |
| updated_time | timestamp | 更新时间 |

同一知识库内 `content_hash` 唯一，用来识别重复上传。

## 4. document_task 处理任务

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| document_id | bigint | 所属文档，唯一 |
| status | varchar | PENDING / PROCESSING / SUCCEEDED / FAILED |
| retry_count | int | 已重试次数 |
| max_retries | int | 最大重试次数 |
| error_message | varchar | 失败原因 |
| created_time | timestamp | 创建时间 |
| updated_time | timestamp | 更新时间 |

上传接口只负责创建任务，后台线程池处理文档，失败会按配置重试。

## 5. document_chunk 文本块

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| document_id | bigint | 所属文档 |
| chunk_index | int | 文档内顺序 |
| content | text | 文本内容 |
| content_hash | varchar | 文本块哈希 |
| metadata | jsonb | 标题等元数据 |
| embedding | vector(N) | 向量，N 由模型决定 |
| created_time | timestamp | 创建时间 |

检索时 SQL 大致是：

```sql
SELECT c.id, c.content, c.metadata, d.file_name,
       c.embedding <=> ?::vector AS distance
FROM document_chunk c
JOIN document d ON d.id = c.document_id
WHERE d.knowledge_base_id = ? AND d.status = 'COMPLETED'
ORDER BY c.embedding <=> ?::vector
LIMIT ?;
```

`<=>` 是向量距离运算符，距离越小表示越相似。

## 6. chat_conversation 会话

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| knowledge_base_id | bigint | 使用的知识库 |
| user_id | bigint | 用户 ID，暂为空 |
| title | varchar | 会话标题 |
| created_time | timestamp | 创建时间 |
| updated_time | timestamp | 更新时间 |

## 7. chat_message 消息

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| conversation_id | bigint | 所属会话 |
| role | varchar | user / assistant |
| content | text | 消息内容 |
| prompt_tokens | int | 输入 Token 数，暂为空 |
| completion_tokens | int | 输出 Token 数，暂为空 |
| created_time | timestamp | 创建时间 |

## 8. audit_log 审计日志

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| user_id | bigint | 操作人 |
| action | varchar | 操作类型 |
| target_type | varchar | 目标类型 |
| target_id | bigint | 目标 ID |
| detail | varchar | 操作详情 |
| created_time | timestamp | 创建时间 |

## 9. sql_diagnosis 诊断记录

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| user_id | bigint | 操作人 |
| sql_text | text | 原始 SQL |
| data_source | varchar | 数据源标识 |
| explain_json | jsonb | 执行计划 |
| risk_level | varchar | HIGH / MEDIUM / LOW |
| risks_json | jsonb | 规则风险清单 |
| advice | text | AI 诊断建议 |
| knowledge_base_id | bigint | 可选，使用的知识库 |
| created_time | timestamp | 创建时间 |

## 10. document_version 文档版本

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| document_id | bigint | 所属文档 |
| version | int | 版本号 |
| file_name | varchar | 文件名 |
| file_type | varchar | 文件类型 |
| file_size | bigint | 文件大小 |
| file_path | varchar | 文件路径 |
| content_hash | varchar | 内容哈希 |
| created_time | timestamp | 创建时间 |

## 11. knowledge_base_member 知识库成员

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| knowledge_base_id | bigint | 知识库 ID |
| user_id | bigint | 用户 ID |
| role | varchar | OWNER / MEMBER |
| created_time | timestamp | 加入时间 |

## 12. model_usage 模型用量

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| user_id | bigint | 用户 |
| scene | varchar | 场景：chat / sql / evaluation |
| model | varchar | 模型名 |
| prompt_tokens | int | 输入 Token |
| completion_tokens | int | 输出 Token |
| estimated_cost | numeric | 估算费用 |
| created_time | timestamp | 创建时间 |

## 13. auth_token 登录令牌

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| user_id | bigint | 用户 |
| token | varchar | 令牌，唯一 |
| expires_at | timestamp | 过期时间 |
| created_time | timestamp | 创建时间 |

## 删除策略

- 删除文档：先标记 `DELETED`，再删除它的文本块；
- 删除文档时，未完成的任务会被标记为 `FAILED`；
- 删除后再次上传相同内容，会重新创建任务处理，不再返回旧记录；
- 删除知识库：使用 `DISABLED`，不物理删除；
- 检索时只查状态为 `COMPLETED` 的文档。
