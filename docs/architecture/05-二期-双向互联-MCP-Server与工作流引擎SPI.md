# 05 · 二期设计：双向互联（MCP Server × 工作流引擎 SPI）

> 状态：待评审 · 前置阅读：[01-总体架构设计](01-总体架构设计.md)
> 一句话：把 DevMind 从「单向接入层」升级为「双向枢纽」——自有能力以 MCP 暴露给第三方 AI 平台；第三方工作流引擎（n8n）以平权姿态接入工作流 SPI。原定位「接入即能力」不变，本设计是其完成态。

## 0. 目标与非目标

**目标（按优先级）：**

1. DevMind 作为 **MCP Server**，把自有能力（检索 / RAG / 工作流执行 / 技能）暴露给任何 MCP 客户端（Claude、Cursor、DSH 等）；
2. **WorkflowEngine SPI**：内置引擎与外部引擎（n8n，经其 MCP Server Trigger）平权可插拔，外部步骤享受同一套治理（校验 / 超时 / 重试 / 断点 / 审计）；
3. 两刀共享同一治理模型：第三方调用与本地调用在审计日志、用量配额、技能沉淀中**不可区分**。

**非目标（明确不做）：**

- 运行时热装插件（PF4J / OSGi）——配置驱动即可，重启无感；
- Maven 多模块拆分——等出现第三个 SPI 消费者再说；
- 前端插件化；
- 改变任何现有默认行为——所有新能力默认**关闭**。

## 1. 现状盘点（本期依赖的既有接缝）

| 接缝 | 位置 | 本期用法 |
| --- | --- | --- |
| Mock/Real 双模网关 | `ai/ChatRouter`、`MockAiModelGateway` | 证明「可替换实现」模式已在本项目验证过 |
| 工作流执行器 | `workflow/WorkflowExecutor`（sealed StepUnit：顺序/并行/If/Loop/审批）+ `WorkflowService.run/runStream`（信号量闸门、分级重试、断点恢复） | SPI 的内置实现，行为零变化 |
| 工具语义档案 | `tool/ToolSemanticRepository`、`ToolDefinitionRepository` | MCP tool 的 description 直接复用语义档案，不另写一份 |
| 工具授权过滤 | `AgentService` accessibleToolNames（:325 一带） | MCP 暴露的工具同样过授权过滤 |
| MCP 客户端依赖 | `pom.xml` 已含 `spring-ai-mcp`（Spring AI 1.0.0 BOM） | 第二刀的 n8n 接入通道；第一刀用同源的 mcp-server starter |
| 凭据模式 | `apiKeyEnv` 环境变量引用 | n8n 凭据沿用，不新增硬编码 |

## 2. 第一刀：MCP Server（`com.devmind.mcp` 新包）

**技术选型**：`spring-ai-starter-mcp-server-webmvc`（与现有 Web 栈同源，SSE / Streamable HTTP 传输）。第一周先做 1 天联通性 spike：验证 Spring AI 1.0.0 的 server 端与主流客户端（DSH / Claude Desktop）协议版本兼容。

**首版暴露工具（6 个，白名单配置逐个开）：**

| 工具 | 映射到 | 说明 |
| --- | --- | --- |
| `kb_search` | 语义检索服务（`/search` 同源，不调 LLM） | 只读，最先上 |
| `kb_chat` | RAG 问答（带引用溯源） | 只读 |
| `workflow_run` | `WorkflowService.run` | 写操作类，带 `confirm` 开关；审批流照走 approvals |
| `skill_invoke` | 技能执行（按声明注入接口） | 二期主打的复用叙事 |
| `workflow_generate` | NL 生成工作流草稿 | P2 |
| `sql_diagnose` | `SqlDiagnosisService` | **默认永关**——真实模式把用户 SQL 拼进 Statement，无 allowlist 前不暴露 |

**关键设计点：**

1. **工具描述零重复**：MCP tool 的 name/description/参数 schema 从 `ToolSemanticRepository` 语义档案生成，OpenAPI 导入的接口天然可获得 MCP 描述（「接入即能力」在出口侧同样成立）；
2. **身份映射**：每个 MCP 会话映射为一个服务账号（服务端注入身份，不信任任何客户端头），审计日志记录 `source=mcp`，用量配额（日次数/日成本）照拦——这是「治理平权」的落点；
3. **配置形态**（对齐现有 `DEVMIND_*` 风格）：

```yaml
DEVMIND_MCP_SERVER_ENABLED=false          # 总开关，默认关
DEVMIND_MCP_SERVER_ENDPOINT=/mcp          # 端点路径
DEVMIND_MCP_TOOLS=kb_search,kb_chat       # 工具白名单，逗号分隔
DEVMIND_MCP_SERVICE_USER=mcp-service      # 审计/配额用的服务账号 id
```

**最小演示路径（第一周末态）**：DSH 的 MCP 配置指向 `http://localhost:8080/mcp` → 在 DSH 会话里直接查 DevMind 知识库（`kb_search`）→ DevMind 审计页出现 `source=mcp` 的记录 → 关掉白名单里的工具，DSH 侧立即不可见。

## 3. 第二刀：WorkflowEngine SPI + n8n（`workflow/spi` 新包）

**SPI 契约（首版刻意小）：**

```java
public interface WorkflowEngine {
    String id();                                       // "builtin" / "n8n"
    boolean supports(WorkflowSpec spec);               // 引擎能力声明
    WorkflowHandle run(WorkflowSpec spec, RunContext ctx);   // 异步启动
    EngineStepStatus poll(WorkflowHandle handle);      // 轮询状态（幂等）
    Map<String, Object> result(WorkflowHandle handle); // 输出变量
}
```

**要点：**

1. **内置引擎 = 现有 `WorkflowExecutor` 的适配器**。契约测试套件（`WorkflowEngineContractTest`）定义统一行为基线：超时、分级重试只对瞬时故障、断点恢复幂等、错误输出识别——现有 `WorkflowExecutorTest` 的 32 个断言迁到契约上跑，**保证内置行为零变化**；
2. **引擎选择是 workflow 定义的一个字段**（`engine: builtin`，缺省 builtin），不是全局配置——同一平台里不同工作流可以走不同引擎；
3. **n8n 适配器**：用已依赖的 spring-ai-mcp **客户端**注册 n8n 的 MCP Server Trigger 端点；一个 n8n 工作流 = 一个外部 step，`{{x.field}}` 变量模板照用。n8n 侧的输入 schema 较粗，适配层做**显式 schema 声明**（不猜）；
4. **外部步骤同治理**：外部 step 同样走 30s 超时、分级重试、断点恢复（轮询幂等是 SPI 契约的一部分）、轨迹审计——不许外部引擎成为治理盲区。

**最小演示路径（第二周末态）**：n8n 建一个「发消息」工作流开 MCP Trigger → DevMind 工作流编排：内置 step（查知识库）+ 外部 step（n8n 发消息）混排 → 跑到外部 step 时 kill 进程 → 重启后断点续跑，外部 step 按轮询幂等正确收尾。

## 4. 治理平权（两刀共享的验收标准）

验收问题只有一个：**一次 MCP 调用 / 一个外部 step,和一次本地工具调用,在审计日志、用量统计、技能依赖记录里是否无差别?**

- 审计：`source` 字段区分来源（local / mcp / engine-n8n），其余字段同构；
- 配额：`ModelUsage` 口径不变，MCP 调用消耗的模型用量计入调用方服务账号；
- 技能：技能声明的接口依赖不区分内外——外部引擎的 step 也进依赖图。

同时补两个产品指标进 README（替代自指 MRR 的展示位）：**「从接入到首次被 AI 成功调用的分钟数」**、**「技能复用率」**——这两个本期真的测得出来。

## 5. 分支与测试策略

- 分支：`feature/mcp-workflow-2.0`，两刀各自独立合并点；每合并点 `mvn test` 全绿 + `scripts/demo/demo.ps1` 通过；
- 新增测试约 40 个：MCP 工具映射（协议层用 Spring AI 测试工具 + 假客户端）、SPI 契约测试、n8n 适配器（WireMock 假 MCP 端点）、治理平权（source 标记 / 配额计入）；
- 回滚：两个开关默认关，关闭 = 回到现状，无迁移成本。

## 6. 里程碑

| 周 | 交付 |
| --- | --- |
| W1 | 联通性 spike → MCP server 骨架 + `kb_search`/`kb_chat` + 服务账号/审计/配额接入 → DSH 联调演示 |
| W2 | WorkflowEngine SPI + 契约测试迁移 → n8n 适配器 → 混排 + 断点恢复演示 |
| W3（可选） | `workflow_generate`/`skill_invoke` 暴露、README「二期」叙事 + 两个产品指标上线 |

## 7. 风险与开放问题

1. **协议兼容**：Spring AI 1.0.0 的 MCP server 与客户端 spec 版本错位——W1 第一天 spike，不兼容则锁传输方式（SSE）解决；
2. **n8n Trigger 的 schema 粗糙**：适配层显式声明 schema，不做推断；n8n 版本差异写进 README 部署说明；
3. **`sql_diagnose` 注入面**：未做只读 allowlist 前永不暴露（白名单里物理不存在）；
4. **开放问题（评审时定）**：a) 首版白名单是否包含 `workflow_run`（写操作）；b) 服务账号是否需要按调用方区分（Claude 一个账号、DSH 一个账号，便于配额分账）；c) MCP 端点是否要求 token（即使不上线，demo 给别人看时别裸奔）。
