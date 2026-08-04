# DevMind 检索链路深度优化（阶段 X）

## 背景

检索命中率已达 90%，但存在三处短板：

1. 真实 Rerank 链路受智谱限流影响不稳定，重复请求重复消耗模型额度；
2. 不同问题类型（术语 / FAQ / 开放问答）走同一检索权重策略，针对性不足；
3. 回答引用只有文本，缺少结构化溯源入口；评估需手动操作。

本阶段完成：查询路由、Rerank 结果缓存、引用预览、评估自动化。

## 一、查询路由（QueryRouter）

### 设计

按问题特征选择混合检索权重策略，纯 Java 无外部依赖，可单测：

| 特征 | 判定 | 路由 |
| --- | --- | --- |
| 术语/缩写/代码特征 | `EXPLAIN/LIMIT/JOIN/INDEX/Nginx/RAG/SQL/BM25/HNSW…`、全大写缩写、代码片段 | 关键词优先 `vector 0.4 / keyword 0.6` |
| 口语/通用问题 | 无上述特征 | 混合 `vector 0.7 / keyword 0.3` |
| 空/空白问题 | `isBlank` | 回退混合 |

### 接入点

- `ChatService.chat()`：`QueryRouter.route(question)` 后使用 `route.vectorWeight()/route.keywordWeight()`；
- `ChatService.chatAcrossKnowledgeBases()`：多库聚合问答同样接入。

### 代码位置

`src/main/java/com/devmind/retrieval/QueryRouter.java`

## 二、Rerank 结果缓存

### 设计

`RerankService` 增加模型 Rerank 结果缓存：

- 键：`question + "|" + chunkIds.join(",")`（问题 + 结果集合双因子）；
- 容量上限 500，满时整体清空（简单有效）；
- model 模式下先查缓存 → 命中直接返回；未命中调用 `ModelReranker` 并写入；
- model 调用失败（如 429）→ WARN 日志 + 回退启发式排序，不影响回答。

### 效果

- 相同问题 + 相同候选集重复评估命中缓存，避免重复消耗智谱额度；
- 限流期间仍能返回稳定排序结果。

### 代码位置

`src/main/java/com/devmind/retrieval/RerankService.java`

## 三、引用溯源预览

### 设计

`ChatView.vue` 引用卡片增加「预览」按钮（`v-if="ref.documentId"`）：

- 点击调用 `openModal` 打开 `DocumentPreview` 模态；
- 模态加载 `GET /api/documents/{id}/content` 展示原文；
- 复用全局 modal store，无需新路由。

## 四、评估自动化脚本

### 用法

```powershell
pwsh -File .\scripts\evaluate.ps1 -KbId 3 -Mode heuristic
# 可选参数：-User demo -Password demo123 -Base http://localhost:8080
```

### 流程

1. 登录（POST /api/auth/login 拿 token）；
2. 跑 50 条样本评估（POST /api/evaluations/retrieval）；
3. 输出主题命中率；
4. 归档报告到 `docs/eval-reports/eval-kb{KbId}-{Mode}-{stamp}.md`。

## 五、模型降级与熔断（限流治理）

验证阶段 X 期间智谱 API 持续 429 限流，暴露两个问题：上游挂起时请求无限阻塞（120s+），且每次问答都吃满重试退避。本阶段新增三层降级/熔断治理：

### 1. RestClient 超时（`config/HttpClientConfig`）

- 覆盖默认 `RestClient.Builder`：连接超时 5s、读取超时 30s；
- 智谱与备用模型调用均生效，上游挂起时快速失败而非无限阻塞。

### 2. 429 快速失败（`ZhipuRestModelGateway`）

- 429 限流是账户级持续状态，短时重试大概率仍 429：识别到 429 直接放弃重试；
- 非 429 错误（网络/5xx）保留 3 次指数退避重试；
- 效果：embedding 429 → 立即降级关键词检索，chat 429 → 立即交给 ChatRouter。

### 3. ChatRouter 熔断 + 本地 RAG 兜底（`ChatRouter` / `LocalRagAnswerer` / `ChatService`）

- 429 限流：第一次失败即打开熔断 60s；其他错误连续 3 次失败后熔断；
- 熔断期间不再重试主/备模型，快速失败；
- `ChatService.callModel` 捕获 `MODEL_CALL_FAILED` 后调用 `LocalRagAnswerer` 生成本地摘要回答（标注「本地降级模式」），不再返回 502；
- embedding 失败时 `searchWithFallback` 降级为纯关键词检索（`searchByKeywords`），检索链路在模型不可用期间仍可用；
- 开关：`devmind.local-rag-fallback`（默认 true，环境变量 `DEVMIND_LOCAL_RAG_FALLBACK`）。

### 实测（智谱持续 429 期间）

| 请求 | 耗时 | 结果 |
| --- | --- | --- |
| 首次（触发熔断） | 32.6s | 本地降级回答 + 5 引用（embedding/chat 各 429 快速失败） |
| 熔断期间第二次 | 0.1s | 本地降级回答 + 4 引用（直接跳过模型） |
| 引用预览 | - | 引用卡片「预览」按钮打开原文模态 |

## 验证结果

| 项 | 实测 |
| --- | --- |
| 后端单测 | 67/67 通过（新增 `QueryRouterTest` 5 + `RerankServiceCacheTest` 3 + `LocalRagAnswererTest` 4 + `ChatRouterCircuitBreakerTest` 3 + `ChatServiceTest` 降级 1） |
| 前端测试 | 21/21 通过 |
| 前端构建 | 成功（产物更新至 `src/main/resources/static`） |
| 部署 | docker compose 重建 app，health=UP |
| 评估脚本 | 50 条命中 45，命中率 90%，报告归档 `docs/eval-reports/eval-kb3-heuristic-20260804_232458.md` |
| 浏览器 | 新版问答页（多选知识库）正常；引用预览按钮 + 模态打开原文；模型限流时页面显示本地降级回答而非报错 |

## 说明与后续

- 验证期间智谱 API 多次 429（评估脚本 50 条 + 问答触发速率限制），属外部限流；Rerank 缓存、本地降级、熔断与超时治理正是为缓解此类问题而设计；
- 阶段 Y（性能与规模化）可继续验证 HNSW 参数、批量入库与压测。
