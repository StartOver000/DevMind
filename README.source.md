# DevMind

> 面向研发团队的 AI 知识检索与智能诊断平台

DevMind 是一个基于 Spring Boot 和 RAG 的研发知识库项目。用户可以上传 Markdown、PDF 等技术文档，系统完成文档解析、文本切分、向量化和检索，并结合大语言模型回答研发问题。

项目采用“先完成最小闭环，再逐步工程化”的路线：前期几天内完成文档问答 MVP，后续扩展异步解析、权限隔离、语义缓存、混合检索、引用溯源和 SQL 执行计划诊断。

## 设计文档

- [总体架构设计](docs/01-总体架构设计.md)
- [数据库与向量模型](docs/02-数据库与向量模型.md)
- [MVP 接口契约](docs/03-MVP接口契约.md)
- [分阶段实施计划](docs/04-分阶段实施计划.md)

---

## 一、项目定位

### 项目名称

**DevMind：研发知识检索与智能诊断平台**

### 一句话介绍

> 将研发技术文档、项目规范和数据库知识统一接入知识库，通过 RAG 检索回答研发问题，并逐步扩展 SQL 执行计划诊断能力。

### 目标用户

- Java 后端开发人员
- 测试和运维人员
- 技术负责人
- 需要查询项目规范和技术文档的研发团队

### 当前阶段目标

先完成以下最小业务闭环：

```text
上传 Markdown/PDF
    -> 解析文本
    -> 文本切分
    -> 生成向量
    -> 写入向量库
    -> 用户提问
    -> 相似度检索
    -> 拼接上下文
    -> 大模型回答
```

---

## 二、技术版本基线

以下版本作为项目初始开发基线，先固定版本，避免开发过程中频繁升级依赖。

### 后端

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 17 | Java 运行环境 |
| Spring Boot | 3.3.4 | 后端基础框架 |
| Spring AI | 1.0.0 | ChatClient、Embedding、VectorStore |
| Maven | 3.9+ | 项目构建 |
| Lombok | 1.18.34 | 简化实体和日志代码，可选 |
| MyBatis-Plus | 3.5.7 | 业务数据访问，可选 |
| Spring Validation | 随 Spring Boot | 请求参数校验 |
| Spring Web | 随 Spring Boot | REST API |

### 数据与中间件

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| PostgreSQL | 16 | 业务数据和向量数据存储 |
| pgvector | 0.7.4 | PostgreSQL 向量检索扩展 |
| Redis | 7.2+ | 后续用于语义缓存、任务状态和限流 |
| Docker Compose | 2.x | 本地启动 PostgreSQL、Redis 等依赖 |

### 文档解析与模型

| 组件 | 版本/要求 | 用途 |
| --- | --- | --- |
| Apache Tika | 2.9.2 | PDF、DOCX 等文档解析 |
| CommonMark | 0.22.0 | Markdown 解析，可选 |
| OpenAI-compatible API | 兼容即可 | 对话模型和向量模型 |
| Embedding 模型 | 维度以实际模型为准 | 文本向量化 |

> 模型服务不在本项目中固定为某一家供应商。可以使用 OpenAI、Azure OpenAI、DeepSeek、通义千问、智谱或本地 Ollama，只要提供兼容的 Chat Completions 和 Embeddings 接口即可。
>
> 如果使用本地模型，建议先使用 Ollama 0.3+；模型名称和向量维度以本机实际安装版本为准，不要把模型名称硬编码到业务代码中。

---

## 三、建议安装环境

### 必需

- JDK 17
- Maven 3.9+
- Docker Desktop
- Git
- 一个 OpenAI-compatible 大模型 API Key

### 推荐

- IntelliJ IDEA 或 VS Code
- PostgreSQL 16 Docker 容器
- Redis 7.2 Docker 容器
- Postman 或 Apifox

### 本地中间件

后续实现时建议提供 `docker-compose.yml`，至少包含：

```text
PostgreSQL 16 + pgvector
Redis 7.2
```

初始阶段不需要安装 Kafka、RocketMQ、Elasticsearch 或 Milvus。等 MVP 闭环稳定后，再根据实际扩展需求引入组件。

---

## 四、MVP 范围

MVP 只实现单用户、单知识库、同步处理和基础问答，不提前引入复杂权限和分布式架构。

### 4.1 文档管理

支持：

- 上传 Markdown 文件；
- 上传 PDF 文件；
- 查看文档列表；
- 删除文档；
- 查询文档处理状态。

文档状态建议：

```text
UPLOADED       已上传
PROCESSING     处理中
COMPLETED      处理完成
FAILED         处理失败
DELETED        已删除
```

### 4.2 文档处理

初版流程：

1. 校验文件类型和大小；
2. 保存文件元数据；
3. 使用 Apache Tika 或 Markdown Parser 提取文本；
4. 按段落和固定长度切分文本；
5. 为每个文本块生成向量；
6. 写入 PostgreSQL + pgvector；
7. 更新文档处理状态。

初始切分策略建议：

- chunk size：500-800 tokens；
- overlap：50-100 tokens；
- 尽量按标题、段落和代码块边界切分；
- 每个向量块保存文档 ID、标题、页码或段落信息，便于后续引用来源。

### 4.3 问答接口

建议接口：

```text
POST /api/knowledge-bases/{knowledgeBaseId}/chat
```

请求示例：

```json
{
  "question": "MySQL 深分页为什么会变慢？",
  "topK": 5,
  "conversationId": null
}
```

返回示例：

```json
{
  "answer": "......",
  "references": [
    {
      "documentId": 1,
      "documentName": "MySQL专题.md",
      "content": "......",
      "score": 0.86
    }
  ]
}
```

### 4.4 MVP 暂不实现

- 多租户；
- 复杂 RBAC；
- MQ 异步文档解析；
- 混合检索；
- 自动索引创建；
- 多模型智能路由；
- 在线修改数据库结构；
- 自动执行 AI 生成的 SQL；
- 生产环境部署。

---

## 五、建议的模块划分

```text
DevMind
├── devmind-api              REST 接口、DTO、统一异常处理
├── devmind-document         文件上传、文档元数据、解析和切分
├── devmind-retrieval        Embedding、VectorStore、相似度检索
├── devmind-chat             Prompt、上下文拼接、ChatClient 调用
├── devmind-knowledge        知识库和文档关联关系
├── devmind-infrastructure   PostgreSQL、Redis、文件存储配置
└── devmind-common            公共响应、枚举、异常和工具类
```

MVP 可以先使用单体模块完成，代码包按领域划分即可。只有当功能稳定后，再考虑拆分 Maven Module 或微服务，避免为了体现架构而过早增加复杂度。

---

## 六、核心数据表建议

### knowledge_base

```text
id
name
description
status
created_by
created_time
updated_time
```

### document

```text
id
knowledge_base_id
file_name
file_type
file_size
file_path
content_hash
status
error_message
created_by
created_time
updated_time
```

### document_chunk

```text
id
document_id
chunk_index
content
content_hash
metadata
embedding
created_time
```

### chat_conversation

```text
id
knowledge_base_id
user_id
title
created_time
updated_time
```

### chat_message

```text
id
conversation_id
role
content
prompt_tokens
completion_tokens
created_time
```

初期可以使用 Spring AI 提供的向量表结构，后续再根据检索和元数据过滤需求调整。不要一开始就把所有聊天记录、模型参数和监控字段设计得过度复杂。

---

## 七、后续演进路线

### 第一阶段：MVP

```text
Markdown/PDF 上传
-> 同步解析
-> 向量入库
-> Top-K 检索
-> RAG 问答
-> 返回引用来源
```

### 第二阶段：工程化

```text
文件上传
-> 任务表
-> 异步文档解析
-> Redis/MQ 任务分发
-> 失败重试
-> 幂等处理
-> 状态查询
```

可加入：

- 文档内容哈希，避免重复向量化；
- 任务状态机；
- 失败重试次数；
- 任务超时扫描；
- 文档处理监控；
- 大文件分片处理。

### 第三阶段：检索质量优化

```text
向量检索
+ 关键词检索
+ 元数据过滤
+ Rerank
-> 混合检索结果
```

可加入：

- 标题权重；
- 文档类型过滤；
- 代码块特殊处理；
- 相似问题缓存；
- 召回结果去重；
- 引用来源和置信度展示。

### 第四阶段：SQL 智能诊断模块

```text
输入 SQL
    -> 获取表结构
    -> 执行 EXPLAIN
    -> 解析执行计划
    -> 检索 MySQL 优化知识
    -> AI 生成诊断建议
    -> 输出索引、改写和风险说明
```

注意：AI 只负责分析和生成建议，不允许直接执行 AI 生成的 DDL 或 DML。后续如需验证，应连接隔离测试库，并加入人工确认流程。

### 第五阶段：企业化能力

- 多用户和 RBAC；
- 多知识库隔离；
- 文档版本管理；
- 团队共享和审计日志；
- 多模型路由；
- Token 成本统计；
- Prometheus/Micrometer 指标；
- Docker Compose 和 CI/CD 部署。

---

## 八、关键工程约束

1. 所有外部模型调用必须通过配置注入，禁止把 API Key 写入代码或提交到 Git。
2. 模型调用失败时返回明确错误，不允许静默返回模型编造的结果。
3. 问答 Prompt 必须要求模型优先依据检索上下文回答，无法确认时明确说明“知识库中没有足够信息”。
4. 文档处理必须保存状态，不能只依赖内存变量。
5. 文档重复上传需要通过 `content_hash` 做幂等判断。
6. 向量维度必须和 Embedding 模型保持一致，切换模型时不能直接复用旧向量。
7. MVP 不引入不必要的 MQ、搜索引擎和微服务拆分。
8. 所有 SQL 诊断建议必须标注“建议验证”，禁止自动修改生产数据库。
9. API 返回统一结构，并为错误、超时和模型限流设计明确错误码。
10. 每个阶段都必须能够独立运行和演示，不要等全部功能完成后才验证。

---

## 九、首批接口清单

```text
POST   /api/knowledge-bases
GET    /api/knowledge-bases
DELETE /api/knowledge-bases/{id}

POST   /api/knowledge-bases/{id}/documents
GET    /api/knowledge-bases/{id}/documents
GET    /api/documents/{id}
DELETE /api/documents/{id}

POST   /api/knowledge-bases/{id}/chat
GET    /api/conversations/{id}/messages
```

MVP 先实现文档上传、文档列表、问答三个核心接口，其余接口可以按需要补充。

---

## 十、验收标准

### MVP 验收

- 能启动 Spring Boot 服务；
- 能通过 Docker 启动 PostgreSQL + pgvector；
- 能上传 Markdown 文档；
- 能上传至少一种 PDF 文档；
- 能完成文本解析、切分和向量入库；
- 能根据问题检索相关文本块；
- 能调用大模型生成回答；
- 回答中包含引用来源；
- 无相关资料时能明确返回无法确认，而不是编造答案；
- API Key 不出现在代码和 Git 历史中。

### 后续版本验收

- 文档重复上传不会重复向量化；
- 文档解析失败可以重试；
- 大文件处理不会阻塞 HTTP 请求；
- 用户只能检索有权限的知识库；
- 能查看模型耗时、Token、检索耗时和失败率；
- SQL 诊断能展示 EXPLAIN 原始结果、AI 建议和验证风险。

---

## 十一、给后续 AI 执行者的开发要求

请基于本 README 按以下顺序实施：

1. 先生成项目骨架和 `pom.xml`；
2. 提供 `docker-compose.yml`，启动 PostgreSQL 16 + pgvector 和 Redis 7.2；
3. 完成数据库初始化脚本；
4. 先实现 Markdown 上传、解析、切分和向量入库；
5. 再实现 Top-K 检索和 RAG 问答接口；
6. 使用接口测试验证完整链路；
7. 最后补充 PDF 解析、引用来源和基础异常处理；
8. 每完成一个阶段都运行测试，不要一次性生成全部复杂功能；
9. 暂时不要实现 MQ、权限、多租户和 SQL 自动执行；
10. 输出启动命令、环境变量说明和接口调用示例。

实现过程中必须优先保证 MVP 可运行，再逐步扩展，不要为了提前体现“分布式”而引入尚未解决的基础设施复杂度。
