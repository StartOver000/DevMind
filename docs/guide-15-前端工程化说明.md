# DevMind 阶段 L：正式前端工程化 — 完成说明

## 目标

把静态页面升级成正式前端项目：组件化、路由、请求封装、单元测试，构建产物由 Spring Boot 托管。

## 技术选型

| 项目 | 选择 | 说明 |
| --- | --- | --- |
| 框架 | Vue 3（组合式 API + `<script setup>`） | 模板语法与原生 HTML 接近，迁移成本低 |
| 构建 | Vite 5 | 开发热更新 + 生产构建 |
| 路由 | vue-router 4（hash 模式） | 5 个页面 `/kb` `/chat` `/sql` `/usage` `/eval` |
| 测试 | Vitest + @vue/test-utils + jsdom | 21 个单元测试用例 |
| 托管 | Spring Boot `static/` | 构建产物输出到 `src/main/resources/static` |

## 项目结构

```text
frontend/
├── package.json
├── vite.config.js          # 构建输出到 ../src/main/resources/static
├── index.html
├── src/
│   ├── main.js
│   ├── App.vue             # 顶栏 + 路由出口 + Toast + Modal
│   ├── router/index.js     # 5 条路由
│   ├── api/client.js       # 请求封装（Token/X-User-Id/JSON/错误）
│   ├── stores/             # 轻量状态：toast / modal / session / kbs
│   ├── styles/main.css     # 全局样式（从原 styles.css 迁移）
│   ├── components/         # TopBar / Toast / Modal / KbSidebar /
│   │                       # MemberPanel / DocTable / TaskDetail /
│   │                       # VersionsList / SqlHistory
│   └── views/              # KnowledgeBase / Chat / SqlDiagnosis /
│                           # Usage / Evaluation
└── tests/                  # client / components 单元测试
```

## 常用命令

```bash
cd frontend
npm install        # 安装依赖
npm run dev        # 开发模式（/api 代理到 localhost:8080）
npm run build      # 生产构建，输出到 src/main/resources/static
npm run test       # 运行单元测试（21 用例）
```

## 请求封装要点

`src/api/client.js`：

- 自动附加 `X-User-Id` 请求头；
- 登录后自动附加 `Authorization: Bearer <token>`；
- `FormData` 透传（文件上传），JSON body 自动设置 `Content-Type`；
- 统一解析 JSON，非 2xx 抛出带 `message` 的错误；
- Token 保存在 `localStorage['devmind_token']`。

## 验证结果

| 检查项 | 结果 |
| --- | --- |
| 前端单元测试 | 21/21 通过 |
| 后端单元测试 | 19/19 通过，BUILD SUCCESS |
| Vite 生产构建 | 成功，产物输出到 `static/` |
| 服务健康检查 | `UP` |
| 首页 | 200，Vue 应用正常挂载 |
| 知识库页 | 列表/创建/选择、文档表格、成员管理、上传、版本/任务/删除均正常 |
| 问答页 | 真实模型返回回答 + 引用来源（相似度 0.8568） |
| SQL 诊断页 | 风险清单 + 执行计划 + AI 建议完整渲染 |
| 用量页 | 汇总卡片 + 最近调用列表（含实时新增记录） |
| 评估页 | 命中率 90%（20 条命中 18 条） |
| 弹窗 | SQL 历史、版本历史、任务详情均正常 |
| 登录/退出 | demo/demo123 正常，Token 持久化 |

## 部署说明

1. `cd frontend && npm run build` 生成最新前端产物；
2. `docker compose build app` 重新构建镜像（Dockerfile 会打包 `src/` 到 JAR）；
3. `docker compose up -d app` 重启应用；
4. 访问 `http://localhost:8080/`。

> 注意：`src/main/resources/static` 下的构建产物需要提交到 git，因为
> Dockerfile 只执行 `mvn package`，不会重新构建前端。

## 后续建议（阶段 M 之前可做）

- 前端代码覆盖率提升；
- 接入 ESLint/Prettier 统一代码风格；
- 按需拆分懒加载已经通过路由实现（Vite 代码分割）。
