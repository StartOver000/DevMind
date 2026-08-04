# DevMind 多知识库聚合问答（阶段 V）

## 背景

阶段 V 之前，问答只能针对单个知识库，跨库知识无法综合回答。本阶段实现多知识库聚合问答：一次提问检索多个库，合并去重后统一排序回答。

## 功能与实现

### 1. 聚合接口

- `POST /api/chat/aggregate`
- 请求 `AggregateChatRequest`：`knowledgeBaseIds`（必填，至少一个）、`question`、`topK`、`tags`。

### 2. 多库检索与合并

`ChatService.chatAcrossKnowledgeBases`：

1. 逐库调用 `requireEnabledKnowledgeBaseAccess` 校验权限（任一库无权限即拒绝）；
2. 一次 embed 查询向量（命中阶段 U 的 embedding 缓存）；
3. 逐库 `searchHybrid` 检索（每库召回 topK*2）；
4. 按 `chunkId` 全局去重；
5. 统一 `rerank` 精排取 topK；
6. 复用 `callModel` 生成回答（无会话，conversationId 为 null）。

### 3. 前端

- 问答页知识库改为多选（checkbox 列表）；
- 单选 1 个：走原单库接口（保留会话历史/多轮）；
- 多选多个：走聚合接口（无会话）。

## 验证结果

| 场景 | 结果 |
| --- | --- |
| 聚合 kb[3,1]「深分页为什么慢」 | 返回合并引用：tmp-mysql.md #34（score 0.80）+ RAG检索专题.md #21 |
| 越权防护 | bob 聚合访问无权 kb3 返回 403 |
| 回归 | 后端 51/51、前端 21/21、部署 health=UP |

## 说明

- 聚合问答不创建会话（conversationId=null），前端多选时也不续聊；单选行为与之前完全一致。
