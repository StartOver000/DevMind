# DevMind

面向研发团队的 AI 知识检索与智能诊断平台。本项目实现 DevMind MVP：文档上传、文本解析与分块、向量入库、Top-K 检索、RAG 问答和引用来源返回。

## 技术栈

- JDK 17、Spring Boot 3.3.4
- Spring AI 1.0.0（OpenAI-compatible Chat + Embedding）
- PostgreSQL 16 + pgvector
- Apache Tika（PDF 解析）
- JdbcTemplate（业务数据和向量检索）

## 快速启动

```bash
docker compose up -d
mvn spring-boot:run
```

没有模型 API Key 时，可以用内置模拟模型跑通全链路：

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

## 模型配置

默认使用 OpenAI-compatible API，通过环境变量注入：

```bash
OPENAI_API_KEY=sk-xxx
OPENAI_BASE_URL=https://api.openai.com
OPENAI_CHAT_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
DEVMIND_EMBEDDING_DIMENSIONS=1536
```

也可以把 `OPENAI_BASE_URL` 指向 DeepSeek、通义千问、智谱或 Ollama 的 OpenAI 兼容地址，并同步调整模型名与向量维度。

## 核心接口

```text
POST   /api/knowledge-bases
GET    /api/knowledge-bases
DELETE /api/knowledge-bases/{id}

POST   /api/knowledge-bases/{kbId}/documents
GET    /api/knowledge-bases/{kbId}/documents
GET    /api/documents/{id}
DELETE /api/documents/{id}

POST   /api/knowledge-bases/{kbId}/chat
GET    /api/conversations/{conversationId}/messages

POST   /api/users
GET    /api/users
GET    /api/audit-logs
POST   /api/sql-diagnosis
GET    /api/sql-diagnosis
GET    /api/model-usage
GET    /api/model-usage/summary
POST   /api/knowledge-bases/{id}/members
POST   /api/documents/{id}/versions
POST   /api/documents/{id}/rollback/{version}
POST   /api/auth/register
POST   /api/auth/login
```

完整设计文档在 `docs/` 目录。

## 文档导航

如果你不熟悉 AI 项目，建议按这个顺序看：

- [项目说明（小白版）](docs/guide-01-项目说明-小白版.md)
- [如何运行](docs/guide-02-如何运行.md)
- [接口使用手册](docs/guide-03-接口使用手册.md)
- [项目结构和代码说明](docs/guide-04-项目结构和代码说明.md)
- [数据库设计](docs/guide-05-数据库设计.md)
- [AI 概念白话解释](docs/guide-06-AI概念白话解释.md)
- [下一步开发计划](docs/guide-07-下一步开发计划.md)
- [后续开发计划（一次一阶段）](docs/guide-08-后续开发计划.md)
- [下一阶段计划](docs/guide-11-下一阶段计划.md)
- [再下一阶段计划](docs/guide-13-下一阶段计划.md)
- [生产部署与运维](docs/guide-14-生产部署运维.md)
- [真实模型联调报告](docs/guide-12-真实模型联调报告.md)
- [一键部署说明](docs/guide-09-一键部署说明.md)
- [检索评估报告](docs/guide-10-检索评估报告.md)

原始设计文档：

- [总体架构设计](docs/01-总体架构设计.md)
- [数据库与向量模型](docs/02-数据库与向量模型.md)
- [MVP 接口契约](docs/03-MVP接口契约.md)
- [分阶段实施计划](docs/04-分阶段实施计划.md)
