# DevMind 真实模型联调报告

## 结论

已使用智谱免费模型完成真实模型联调：

- 聊天模型：`glm-4.7-flash`；
- 向量模型：`embedding-2`，维度 1024；
- 接口地址：`https://open.bigmodel.cn/api/paas/v4`。

## 验证结果

| 功能 | 结果 |
| --- | --- |
| 文档向量化 | 成功，文本块写入 pgvector(1024) |
| RAG 问答 | GLM 根据知识库回答，并返回引用来源 |
| SQL 诊断 | GLM 根据执行计划输出详细优化建议 |
| Token 成本统计 | 记录真实 prompt/completion Token 和估算费用 |
| 限流处理 | 免费模型 429 时自动重试 |

实测用量：

- 问答：prompt 259，completion 903；
- SQL 诊断：prompt 194，completion 2000；
- 汇总费用约 `0.00181` 美元（按默认单价估算）。

## 配置方式

在 `.env` 中配置：

```text
DEVMIND_MODEL_MODE=zhipu
DEVMIND_ZHIPU_BASE_URL=https://open.bigmodel.cn/api/paas/v4
DEVMIND_ZHIPU_API_KEY=你的 Key
DEVMIND_ZHIPU_CHAT_MODEL=glm-4.7-flash
DEVMIND_ZHIPU_EMBEDDING_MODEL=embedding-2
DEVMIND_ZHIPU_MAX_TOKENS=2000
DEVMIND_EMBEDDING_DIMENSIONS=1024
```

注意：切换向量模型后，旧向量维度不兼容，需要重建 `document_chunk` 表或换新数据库。
