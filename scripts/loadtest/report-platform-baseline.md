# DevMind 平台自身压测基线（#4）

> 只压平台链路（不碰真实 AI）：mock 模式（`--devmind.model-mode=mock --devmind.embedding-dimensions=1024`），本地 PostgreSQL+pgvector / Redis。
> 压测环境：本机（Windows）单实例，限流阈值临时调高（100000/min）以排除限流干扰——**默认 120/min/路径/用户 是防刷设计**。

## 汇总

| 场景 | 并发 | 时长 | 总请求 | 成功率 | QPS | P50 | P95 | Max | 说明 |
|------|------|------|--------|--------|-----|-----|-----|-----|------|
| 纯语义检索 `POST /api/knowledge-bases/{id}/search` | 16 | 30s | 5508 | 100% | **183.1** | 86ms | 119ms | 170ms | 向量+关键词混合检索（pgvector HNSW + mock embed），不调 LLM；已含 L1 内存缓存 + 池 20 优化 |
| 工作流编排 `POST /api/workflows/{id}/run`（5 个轮流） | 5 | 20s | 4165 | 100% | **208.0** | 21.5ms | 41.2ms | 77.5ms | 并发触发排队串行（per-workflow 闸门），多工作流并行 |
| CRUD 读 `GET /api/tools` | 16 | 20s | 37700 | 100% | **1885** | 7.7ms | 13.2ms | 125.4ms | 列表查询（含鉴权/限流/审计链路） |
| RAG 问答端到端 `POST /api/knowledge-bases/{id}/chat` | 8 | 20s | 33 | 100% | 1.4 | 4554ms | 6924ms | 20085ms | 含 mock LLM 全链路（检索+生成+落库），QPS 由 LLM 链路上限决定 |

## 结论与解读

1. **平台核心链路吞吐**：检索 ~183 QPS / 工作流 ~208 QPS / CRUD ~1885 QPS，均 100% 成功——单机作品场景余量充足。
2. **RAG 端到端慢是 LLM 链路主导**（mock 下仍 ~5s/次：检索+生成+多轮上下文+落库全链路），平台自身检索只占 ~95ms。真实模型下该数值取决于 AI 供应商，压测它没有平台价值（这是当初给 chat 场景加 mock 守卫的原因）。
3. **历史归因纠正**：早前 workflow 压测 3.1% 成功率根因是**全局限流 120/min**（429 RATE_LIMITED），非并发缺陷；调高限流后同参数 100%。
4. **检索性能已优化**（`CachedEmbeddingGateway` L1 内存缓存 + Hikari 池 10→20）：QPS 167.8→183.1（+9%）、P50 94→86ms（-12%）、热请求 ~18ms；详细分析见 `report-search.md`。

## 复现命令
```bash
# 纯检索（需 --confirm-mock-model 确认 mock）
python scripts/loadtest/loadtest.py --endpoint search --kb-id 19 --concurrency 16 --duration 20 --confirm-mock-model --report report-search.md
# 工作流
python scripts/loadtest/loadtest.py --endpoint workflow --workflow-id "11,6,7,8,4" --concurrency 5 --duration 20 --report report-workflow-queue.md
# RAG（mock 确认）
python scripts/loadtest/loadtest.py --endpoint chat --kb-id 19 --concurrency 8 --duration 20 --confirm-mock-model --report report-rag.md
```
