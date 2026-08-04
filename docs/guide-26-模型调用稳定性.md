# DevMind 模型调用稳定性与成本优化（阶段 U）

## 背景

阶段 U 之前，模型调用受智谱 429 限流影响慢（实测单次可达 20s+），且相同/相似文本每次都重复生成 embedding，浪费调用与费用。本阶段实现 Embedding 缓存与并发控制。

## 功能与实现

### 1. Embedding 缓存

- 数据表：`embedding_cache`（`content_hash VARCHAR(64) PRIMARY KEY` + `embedding vector(N)` + `created_time`）。
- 仓库：`EmbeddingCacheRepository`（`find(hash)` / `put(hash, vector)`，vector 以 `?::vector` 写入、读取解析文本格式）。
- 装饰器：`CachedEmbeddingGateway` 实现 `AiModelGateway`——
  - `embed(texts)`：逐文本按内容哈希查缓存；命中直接复用，未命中汇总后一次调用模型并写回缓存；
  - `chat(...)`：透传。

### 2. 并发控制

- `CachedEmbeddingGateway` 内置 `Semaphore(2)`：限制同时进行的模型调用（embed 与 chat 统一受限），降低并发打爆上游限流的概率。
- 智谱网关原有 429 指数退避（5s 起步逐次翻倍）保持。

### 3. 接入方式

- `AiModelConfig` 中 mock/openai/zhipu 三个 `@Bean` 统一返回 `new CachedEmbeddingGateway(真实网关, cache)`，业务代码零改动。

## 验证结果

| 步骤 | `embedding_cache` 行数 |
| --- | --- |
| 初始 | 0 |
| 首次提问「深分页怎么优化」 | 1（写入缓存） |
| 相同问题再次提问 | 1（命中缓存，未新增） |

- 后端 51/51 测试通过；部署 health=UP。

## 说明

- 缓存命中意味着该文本不再调用嵌入模型，直接降低调用量与费用（问答、SQL 诊断、评估均受益）。
- 重试次数/超时配置化暂缓（已有 429 指数退避），后续如需可按 `devmind.model.*` 新增配置。
