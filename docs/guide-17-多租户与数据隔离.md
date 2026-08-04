# DevMind 多租户与数据隔离（阶段 N）

## 背景

阶段 N 之前，DevMind 仅按「用户 + 知识库成员」隔离：知识库所有者可添加成员，成员可访问该知识库。这对个人使用足够，但团队/企业场景下缺少"团队"这一层组织维度，无法实现「团队 A 看不到团队 B 的数据」。

本阶段引入**团队（Team）**作为多租户组织单元，实现团队级数据隔离。

## 数据模型

| 表 | 新增/变更 | 说明 |
| --- | --- | --- |
| `team` | 新增 | id, name(唯一), description, created_by, created_time, updated_time |
| `team_member` | 新增 | (team_id, user_id) 主键, role(OWNER/MEMBER), created_time |
| `app_user` | 加列 | `role` VARCHAR(20) DEFAULT 'USER'（'ADMIN' 为全局管理员） |
| `knowledge_base` | 加列 | `team_id` BIGINT（可空，NULL 表示个人知识库） |
| `audit_log` | 加列 | `team_id` BIGINT（可空，旧记录为 NULL） |

兼容性：所有新列均为可空/带默认值，历史数据无需迁移；`knowledge_base_member` 保留，作为知识库级细粒度成员关系。

## 访问控制规则

对知识库 `kb` 和用户 `u`，允许访问当且仅当满足**任一**条件：

1. `u` 是 `kb` 的知识库成员（`knowledge_base_member`，原逻辑）；
2. `u` 是全局管理员（`app_user.role = 'ADMIN'`）；
3. `kb.team_id` 非空，且 `u` 是该团队成员（`team_member`）。

管理操作（删除知识库、添加/移除成员）额外要求：

1. `kb` 的 owner；
2. 或 `kb` 团队的所有者（`team_member.role = 'OWNER'`）；
3. 或全局管理员。

团队管理操作（添加/移除成员、删除团队）要求团队 OWNER 或全局管理员。

## 接口

### 团队管理 `/api/teams`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/teams` | 创建团队，创建者成为 OWNER |
| GET | `/api/teams` | 我的团队（管理员返回全部） |
| GET | `/api/teams/{teamId}` | 团队详情 + 成员列表 |
| POST | `/api/teams/{teamId}/members` | 添加成员 `{userId, role}` |
| GET | `/api/teams/{teamId}/members` | 成员列表 |
| DELETE | `/api/teams/{teamId}/members/{userId}` | 移除成员（不能移除 OWNER） |
| DELETE | `/api/teams/{teamId}` | 删除团队 |

### 知识库

- `POST /api/knowledge-bases` 请求体新增可选字段 `teamId`：指定后知识库归属该团队（需团队 OWNER 或管理员），团队内成员自动共享访问。
- `GET /api/knowledge-bases` 返回当前用户可访问的知识库（成员 ∪ 团队成员；管理员返回全部），响应含 `teamId`。
- 所有知识库访问接口（文档、问答、评估等）统一走 `requireKnowledgeBaseAccess` 团队隔离校验。

### 审计

- `audit_log` 新增 `team_id` 列；团队创建、成员变更、团队知识库创建/删除等操作都会记录团队维度，做到「操作可追溯到用户和团队」。
- `GET /api/audit-logs` 响应新增 `teamId` 字段。

## 前端

- 新增「团队」页面（`/team`）：创建团队、团队列表、展开查看成员、添加/移除成员、删除团队。
- 知识库创建表单新增「归属团队」下拉：个人（默认）或已有团队。
- 顶部导航新增「团队」入口。

## 验证结果

### 单元测试（后端 33/33 通过）

- `TeamServiceTest`（7 个）：创建即 OWNER、非成员不可访问、OWNER 可管理、非 OWNER 禁止、不能移除团队 OWNER、管理员可管理、非 OWNER 不可删团队。
- `KnowledgeBaseServiceTest`（6 个，含新增 4 个隔离用例）：团队成员可访问团队知识库、非团队成员被拒 403、管理员可访问任意、团队 OWNER 可删除团队知识库。

### 真实接口验证

| 场景 | 结果 |
| --- | --- |
| demo（管理员）团队列表 | 返回「演示团队」+「平台研发组」 |
| 创建团队「平台研发组」 | 成功，创建者为 OWNER |
| 添加 alice 为成员 | 成功 |
| 用团队创建知识库 | 成功，`teamId` 正确写入 |
| alice（团队成员）知识库列表 | 可见团队知识库 |
| bob（非成员）知识库列表 | 完全不可见 |
| bob 直接访问团队知识库 | 403 |
| 审计日志 | `CREATE_KNOWLEDGE_BASE` / `ADD_TEAM_MEMBER` 均带 `teamId` |

### 浏览器实测

- 团队页：创建、展开、成员表格（所有者/成员）、添加成员、移除、删除团队均正常；
- 知识库页：创建表单出现「归属：个人 / 团队：平台研发组 / 团队：演示团队」下拉。

## 备注

- 演示账号 `demo` 初始化为 `ADMIN`，系统自动创建「演示团队」并把 demo 现有知识库归入，便于直接演示团队隔离效果。
- 若需要把已有个人知识库转为团队知识库，目前需通过管理员在数据库层更新 `team_id`，或重新在团队下创建。
