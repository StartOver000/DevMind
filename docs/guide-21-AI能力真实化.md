# DevMind AI 能力真实化（阶段 Q）

## 背景

阶段 P 完成后，检索质量已达标（整体 90%）。本阶段补齐 AI 能力的"真实化 + 可控性"：
- SQL 诊断虽然已走真实模型，但模型失败会直接报错，缺少回退；
- 会话无法管理（无列表/删除接口，历史会话无法续聊）；
- 模型调用无配额，成本不可控。

## 功能与实现

### 1. SQL 诊断真实模型 + 规则回退

- 建议生成走 `ChatRouter`（真实模型链路，含主模型 + 备用模型 + 429 退避）。
- 新增回退：模型调用失败（`ApiException` 或任意异常）时，不再抛 502，而是基于规则引擎风险列表生成结构化诊断建议（标注"需人工验证"），保证诊断接口始终可用。
- 实测：`riskLevel=HIGH`，建议包含全表扫描（`type=ALL`）、文件排序（`Using filesort`）、深分页（`OFFSET`）诊断与复合索引优化建议，并引用知识库参考资料。

### 2. 会话历史管理

- 数据模型：`chat_conversation` 创建时记录归属用户（此前为 NULL，历史会话不回显）。
- 接口：
  - `GET /api/conversations?limit=50`：当前用户的会话列表（按更新时间倒序）；
  - `GET /api/conversations/{id}/messages`：查看消息（已有，含知识库访问校验）；
  - `DELETE /api/conversations/{id}`：删除会话（仅限本人，他人返回 404）。
- 前端：问答页左侧新增「历史会话」面板——列出会话、点击切换并加载最近回答（可继续追问）、删除、新建。

### 3. 用量配额

- 配置 `DevMindQuotaProperties`（`devmind.quota`）：

| 配置 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `daily-calls-limit` | `DEVMIND_QUOTA_DAILY_CALLS` | 0 | 每用户每日模型调用次数上限，0=不限 |
| `daily-cost-limit` | `DEVMIND_QUOTA_DAILY_COST` | 0 | 每用户每日估算费用上限（美元），0=不限 |

- `ModelUsageService.record` 在写入前检查当日累计，超限抛 `429 QUOTA_EXCEEDED`；
- 评估场景（批量任务）不参与用户配额。

## 验证结果

### 单元测试

- 后端 **51/51**（新增 `ModelUsageServiceTest` 3 例：超限拦截 / 配额关闭放行 / 评估场景豁免）；
- 前端 21/21。

### 真实接口验证

| 场景 | 结果 |
| --- | --- |
| SQL 诊断 | `riskLevel=HIGH`，advice 533 字（含诊断 + 索引建议 + 引用知识库） |
| 会话创建 + 列表 | 新会话出现于 `/api/conversations` |
| 会话消息 | `GET /api/conversations/{id}/messages` 返回 user/assistant 两条 |
| 会话删除 | 本人删除成功；他人删除返回 404 |
| 配额 | 默认关闭不拦截；单测覆盖超限拦截逻辑 |

### 浏览器实测

- 问答页「历史会话」面板：会话列表渲染、点击切换加载最近回答（"会话 #14" + 引用来源）、新建、删除按钮齐全。

## 说明

- 配额默认关闭（0），生产可按需通过环境变量开启，避免影响演示。
- 历史会话（阶段 Q 之前创建的，`user_id=NULL`）不会在用户会话列表出现；新会话正常记录归属。
