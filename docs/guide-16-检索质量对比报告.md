# DevMind 检索质量对比报告（阶段 M）

## 1. 背景与目标

阶段 L 完成后，检索评估命中率为 90%（20 条 MySQL 主题样本）。阶段 M 目标：

1. 扩充评估样本到 50 条，覆盖更广主题，暴露真实检索能力边界；
2. 增加文档元数据（标签）过滤，让检索可按来源筛选；
3. 对比真实模型 Rerank 与启发式 Rerank；
4. 对比不同切块策略（语义边界 vs 固定大小）；
5. 全部方案可切换、可回退。

## 2. 评估样本扩充（20 → 50 条）

样本按主题分组：

| 分组 | 数量 | 主题 |
| --- | --- | --- |
| MySQL 索引与深分页 | 20 | 深分页、延迟关联、索引失效、最左前缀、EXPLAIN、filesort、覆盖索引等 |
| SQL 优化场景 | 10 | 游标分页、回表、前缀索引、复合索引、临时表、全表扫描等 |
| RAG 与检索 | 10 | 向量检索、混合检索、RAG、embedding、切块、Rerank、评估 |
| 运维与容量 | 10 | PostgreSQL 向量索引、备份恢复、日志切割、重启、监控、Nginx 等 |

> 实现位置：`RetrievalEvaluationService.QUESTIONS`（`src/main/java/com/devmind/evaluation/`）。

## 3. 元数据过滤

### 实现

- `document` 表新增 `metadata JSONB` 列；
- 上传接口支持 `tags` 参数（逗号分隔），写入文档元数据；
- 入库时文档标签合并到每个 chunk 的 `metadata`；
- 向量/关键词/混合检索均支持 `c.metadata @> ?::jsonb` 过滤；
- 问答接口 `ChatRequest.tags`、评估接口 `EvaluationRequest.tags` 支持按标签过滤；
- 前端：问答页"标签过滤"、评估页"标签过滤"、知识库上传页"标签"输入框。

### 实测

向知识库上传 `Redis缓存专题.md`（tags=`redis,缓存`），提问
`"Redis 缓存穿透怎么解决？"` 并过滤 `tags=["redis"]`：

- 返回 3 个引用全部来自该文档（documentId=5）；
- 每条引用 `metadata.tags=["redis","缓存"]`；
- 回答正确引用"布隆过滤器"。

> 结论：标签过滤可精确限定检索来源，避免跨主题串扰。

## 4. Rerank 对比（启发式 vs 真实模型）

| 指标 | 启发式（heuristic） | 真实模型（model） |
| --- | --- | --- |
| 原理 | 向量分数 + 关键词重叠加权 | LLM 按相关度排序 |
| 50 条命中率 | 56%（28/50） | 受智谱限流影响，运行中（见下） |
| MySQL 主题（30 条） | 87%（26/30） | - |
| 失败回退 | - | 自动回退启发式 ✅ |

### 实测说明

- 启发式 50 条评估：**hits=28/50，hitRate=56%**。其中 MySQL 主题 26/30 命中，
  RAG/运维主题因知识库（kb 3）只有 MySQL 索引类文档而多数未命中——这正说明
  **样本扩充后能暴露知识库内容盲区**，而非检索算法本身的问题。
- 真实模型 Rerank：评估接口新增 `rerankMode=model`。实测中智谱 API 限流（HTTP 429），
  系统按设计**自动回退到启发式**（日志：`model rerank failed, fallback to heuristic`），
  保证了评估可用性，验证了"切换方案可回退"。

> 建议：在智谱额度恢复后重跑 `rerankMode=model`，对比 model 在同等样本下的命中率；
> 后续可接入专用 Rerank 模型（如 bge-reranker）进一步对比。

## 5. 切块策略对比

| 指标 | 语义边界切块（boundary） | 固定大小切块（fixed） |
| --- | --- | --- |
| 切块规则 | 按标题/列表/引用边界切分，保留 overlap | 按最大字符数切分，标题作块前缀 |
| 块数 | 少（语义完整） | 多（长度受控） |
| 单块最大长度 | ≤ maxChars + overlap | ≤ maxChars（更严格） |
| 内容覆盖 | 完整 | 完整 |
| 切换 | `devmind.chunker-strategy=boundary`（默认） | `devmind.chunker-strategy=fixed` |

### 验证

`TextChunkerComparisonTest`（3 个用例）对同一份 MySQL 索引样本文本分别切块，断言：

- 语义边界策略：标题块数 ≥ 5，单块 ≤ 550 字符；
- 固定策略：单块 ≤ 510 字符，标题保留为上下文；
- 两种策略内容均完整覆盖（含"深分页""覆盖索引"等关键词）。

> 结论：boundary 保留语义结构、块数少；fixed 块长更可控。默认保持 boundary 与历史一致，
> 切换 fixed 后重新上传文档即可生效（需重新向量化）。

## 6. 结论与建议

1. **样本扩充有效**：50 条比 20 条更能反映真实检索质量；"命中率下降"主要来自样本主题超出
   知识库内容范围，属于内容覆盖问题而非算法退化。
2. **元数据过滤**已上线，推荐在上传时打标签，检索/评估按需过滤，可显著减少跨主题串扰。
3. **真实模型 Rerank** 已具备切换与回退能力；建议在外部模型额度充足时开启 `rerankMode=model`，
   并评估专用 Rerank 模型。
4. **切块策略**支持 `boundary/fixed` 切换；对不同类型文档（长文/结构化文档）可按需选择。

### 当前运行配置

```text
devmind.chunker-strategy=boundary   # 切块策略
devmind.rerank-mode=heuristic       # 默认 Rerank 模式（模型限流时回退此模式）
devmind.evaluation-top-k=5
```
