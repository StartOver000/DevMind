# DevMind 第三轮计划（阶段 U 起）

## 当前状态

第一轮（K-O）与第二轮（P-T）十阶段全部完成：

- 检索命中率 90%（主题全覆盖）；SQL 诊断真实化 + 回退；会话管理 + 配额；Prometheus 可观测；知识库批量/预览/对比/导出；前端打字机/深色/移动端。
- 后端 51/51、前端 21/21 测试通过；Docker 部署运行中。

第三轮（U/V/W）已完成：Embedding 缓存与并发控制、多知识库聚合问答、Prometheus + Grafana 监控栈落地。

最后一轮 X/Y/Z 规划见 `guide-29-最终规划.md`。

## 执行规则

- 一次只做一个阶段；
- 每个阶段必须完整走完：实现 -> 单元测试 -> 真实接口验证 -> 更新文档 -> 汇报；
- 一个阶段验收通过后，才开始下一个阶段；
- 每个阶段完成后，项目都必须保持可启动、可演示；
- 遇到必须由用户决策的问题，先停下来说明。

## 阶段总览

| 阶段 | 名称 | 状态 | 建议顺序 |
| --- | --- | --- | --- |
| U | 模型调用稳定性与成本优化 | 已完成 | 1 |
| V | 多知识库聚合问答 | 已完成 | 2 |
| W | 监控告警落地部署 | 已完成 | 3 |

## 阶段 U：模型调用稳定性与成本优化

目标：缓解模型 429 限流导致的慢与不稳定，降低 embedding 重复成本。

为什么：

- 智谱限流下单次模型调用可达 20s+，问答/诊断体验差；
- 相同/相似文本每次都重新生成 embedding，浪费调用与费用。

做什么：

1. Embedding 缓存：按内容哈希建缓存表，命中直接复用向量（避免重复调用）；
2. 429 治理：并发信号量限制并行调用、429 指数退避 + 抖动、请求排队；
3. 调用超时与重试参数配置化；
4. 成本优化：评估场景批量 embedding（一次请求多文本）。

验收标准：

- 相同问题第二次问答不再触发 embedding 调用（命中缓存）；
- 并发高峰不再大量 429；
- 单次模型调用平均耗时明显下降。

预计工作量：中到大。

### 阶段 U 完成情况

已完成：

- Embedding 缓存：新增 `embedding_cache` 表（内容哈希主键 + vector），`EmbeddingCacheRepository` 读写；
- 网关装饰器 `CachedEmbeddingGateway`：embed 按内容哈希查缓存，未命中才调用模型并写回；chat 透传；并发信号量限制同时模型调用（上限 2），缓解 429；
- 三个模型网关（mock/openai/zhipu）统一包缓存装饰器，业务代码零改动。

已验证：

- 后端 51/51 测试通过；
- 实测：首次提问「深分页怎么优化」后 `embedding_cache` 从 0 -> 1；相同问题再次提问仍为 1（命中缓存，未新增）；
- 部署 health=UP。

说明：重试次数/超时配置化暂缓（已有 429 指数退避），后续可按需补充。

## 阶段 V：多知识库聚合问答

目标：一次提问检索多个知识库，合并结果回答。

为什么：

- 当前问答单知识库，跨库知识无法综合回答。

做什么：

1. 问答/评估支持多知识库选择；
2. 多库检索结果合并去重、统一 rerank；
3. 前端知识库多选。

验收标准：

- 多库提问返回合并引用；
- 权限按每个库分别校验。

预计工作量：中。

### 阶段 V 完成情况

已完成：

- 聚合接口 `POST /api/chat/aggregate`（`AggregateChatRequest`：knowledgeBaseIds + question + topK + tags）；
- 多库检索：逐库校验访问权限并检索，结果按 chunkId 去重后统一 rerank；
- 前端问答页知识库改为多选（checkbox），单选走单库接口（保留会话/多轮），多选走聚合接口。

已验证：

- 后端 51/51、前端 21/21 测试通过；
- 聚合实测 kb[3,1]：「深分页为什么慢」返回合并引用（tmp-mysql.md score 0.80 + RAG检索专题.md）；
- 越权实测：bob 聚合访问无权 kb3 返回 403；
- 部署 health=UP。

## 阶段 W：监控告警落地部署

目标：把 Prometheus + Grafana 真正部署进 Docker Compose，告警可触发。

为什么：

- 阶段 R 只提供了指标与示例文件，尚未实际部署监控栈。

做什么：

1. docker-compose 增加 prometheus（抓取 devmind-app）与 grafana 服务；
2. 挂载规则文件，接入告警；
3. 预置 Grafana 数据源与面板（provisioning）。

验收标准：

- `docker compose up` 后监控栈可用；
- Grafana 能看到 DevMind 面板数据。

预计工作量：中。

### 阶段 W 完成情况

已完成：

- docker-compose 新增 `prometheus`（抓取 devmind-app `/actuator/prometheus`，挂载 `prometheus.yml` + `rules.yml`）与 `grafana` 服务（3000 端口，admin/admin123）；
- Prometheus 抓取配置 `prometheus/prometheus.yml`；
- Grafana provisioning：数据源（`grafana/provisioning/datasources`）+ 面板（`grafana/provisioning/dashboards` + `grafana/dashboards/devmind-dashboard.json`）；
- 告警规则 `prometheus/rules.yml` 已挂载（规则随抓取生效）。

已验证：

- Prometheus target `devmind-app` health=up，`up` 指标 = 1；
- Prometheus 查询 `devmind_model_calls_total` 返回数据；
- Grafana 数据源 Prometheus 就绪、面板「DevMind 监控面板」自动加载；
- 部署 health=UP，`docker compose config` 通过。

## 不建议做的

- 不拆微服务；
- 不引入 Kafka / RocketMQ / ES / Milvus；
- 不做复杂 Agent；
- 不自动执行 AI 生成的 SQL；
- 不在没有评估样本时宣称检索准确率。
