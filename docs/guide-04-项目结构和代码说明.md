# DevMind 项目结构和代码说明

## 项目目录

```text
DevMind
├── docker-compose.yml          PostgreSQL + pgvector 启动配置
├── pom.xml                     Maven 依赖和构建配置
├── README.md                   项目入口说明
├── examples/                   示例文档，可用来上传测试
├── docs/                       设计文档和用户文档
└── src/main/java/com/devmind/
    ├── DevMindApplication.java 启动类
    ├── common/                 统一错误、traceId、工具类
    ├── config/                 配置、数据库自动建表、模型选择
    ├── user/                   用户和权限
    ├── audit/                  审计日志
    ├── sqldiagnosis/           SQL 执行计划诊断
    ├── knowledge/              知识库领域
    ├── document/               文档上传、解析、切块领域
    │   ├── DocumentTask.java           处理任务模型
    │   ├── DocumentTaskRepository.java 任务表读写
    │   ├── DocumentTaskService.java    异步处理和重试
    │   └── DocumentTaskController.java 任务状态接口
    ├── retrieval/              向量检索领域
    ├── chat/                   会话和 RAG 问答领域
    └── ai/                     模型适配层
```

## 各模块职责

### common

负责通用能力：

- `ErrorCode`：错误码；
- `ApiException`：业务异常；
- `GlobalExceptionHandler`：把异常变成统一 JSON；
- `TraceIdFilter`：给每个请求生成 traceId；
- `HashUtils`：计算 SHA-256。

### config

负责启动配置：

- `DevMindProperties`：读取 `devmind.*` 配置；
- `DatabaseInitializer`：启动时自动建表和建索引；
- `AiModelConfig`：根据 `devmind.model-mode` 选择真实模型或模拟模型。

### knowledge

负责知识库：

- 创建知识库；
- 查询知识库列表；
- 禁用知识库；
- 判断知识库是否存在、是否启用。

### user

负责用户：

- 创建用户；
- 查询用户；
- 判断用户是否存在；
- 通过 `X-User-Id` 请求头识别当前用户。

### audit

负责审计日志：

- 记录创建、删除、上传、问答等操作；
- 按用户查询操作记录。

### sqldiagnosis

负责 SQL 诊断：

- 接收 SQL；
- 在 mock 模式或测试库执行 EXPLAIN；
- 规则识别深分页、全表扫描、filesort 等风险；
- 复用混合检索查询 MySQL 优化资料；
- AI 生成诊断建议；
- 保存诊断记录并写入审计日志。

### document

负责文档处理：

- 文件上传；
- 类型和大小校验；
- SHA-256 去重；
- Markdown 和 PDF 解析；
- 文本切块；
- 保存文档状态。

### document 异步任务

- 上传时只保存文档和任务，立刻返回；
- 后台线程池处理解析、切块和向量化；
- 失败自动重试；
- 定时扫描超时任务；
- 应用启动时自动恢复未完成任务。

### retrieval

负责检索：

- 保存文本块和向量；
- 按知识库过滤；
- 计算相似度；
- 返回 Top-K 结果。

### chat

负责问答：

- 创建会话；
- 保存用户问题和回答；
- 拼接 Prompt；
- 调用模型；
- 返回答案和引用来源。

### ai

负责模型适配：

- `AiModelGateway`：统一模型接口；
- `SpringAiModelGateway`：真实 OpenAI-compatible 模型；
- `MockAiModelGateway`：无 Key 演示用的模拟模型。

## 上传文档的代码流程

```text
DocumentController
  -> DocumentService.upload
    -> 校验知识库
    -> 校验文件类型和大小
    -> 计算 SHA-256
    -> 判断是否重复
    -> 保存文件
    -> 写入 document 记录
    -> 写入 document_task 任务
    -> 立刻返回 UPLOADED + taskId

DocumentTaskService（后台线程）
  -> 任务状态 PROCESSING
    -> DocumentParserRegistry 解析
    -> DefaultTextChunker 切块
    -> AiModelGateway 生成向量
    -> ChunkRepository 写入 pgvector
    -> 任务 SUCCEEDED，文档 COMPLETED
    -> 失败则重试，超过次数后 FAILED
```

## 问答的代码流程

```text
ChatController
  -> ChatService.chat
    -> 校验知识库
    -> 创建或找到会话
    -> 保存用户问题
    -> AiModelGateway 生成问题向量
    -> RetrievalService 搜索 Top-K
    -> 过滤低相似度结果
    -> 拼接 Prompt
    -> AiModelGateway 调用大模型
    -> 保存回答
    -> 返回答案和引用来源
```

## 以后想加东西从哪改

- 加新的文档格式：在 `document/parser` 加一个 Parser；
- 加新的模型供应商：在 `ai` 加一个 Gateway，并在 `AiModelConfig` 注册；
- 改切块策略：改 `document/chunker/DefaultTextChunker`；
- 加权限：在 `knowledge` 和 `retrieval` 增加用户和知识库关系；
- 加异步处理：参考 `docs/guide-07-下一步开发计划.md`。
