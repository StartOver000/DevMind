# DevMind 知识库功能增强（阶段 S）

## 背景

阶段 S 之前，知识库只能单文件上传、无法预览内容、版本对比靠人工打开文件、无导出能力。本阶段补齐批量导入、在线预览、版本对比、知识库导出四项能力。

## 功能与实现

### 1. 批量导入

- 接口：`POST /api/knowledge-bases/{kbId}/documents/batch`
- 请求：multipart `files` 字段（多个文件）+ 可选 `tags`
- 实现：逐文件复用单文件上传逻辑，返回 `{total, failed, items}`；单个文件失败不影响其他文件。
- 前端：上传按钮支持多选（`input multiple`），改用批量接口。

### 2. 文档在线预览

- 接口：`GET /api/documents/{id}/content` 返回文本内容（UTF-8）。
- 权限：需知识库访问权；PDF 返回提示"暂不支持在线文本预览"。
- 前端：文档行新增「预览」按钮，弹窗 `pre` 展示（`DocumentPreview.vue`）。

### 3. 版本对比

- 版本列表接口新增 `currentVersion`（当前版本号；历史快照在 `document_version` 表，当前版本不在快照中）。
- 接口：`GET /api/documents/{id}/compare?from={v}&to={v}`，支持历史版本与当前版本互比（当前版本读取文档当前文件）。
- 前端：版本弹窗新增双列对比（选择 from/to 版本，并排展示两版内容），列表含"当前 vN"选项。

### 4. 知识库导出

- 接口：`GET /api/knowledge-bases/{kbId}/export`，返回 `application/octet-stream` 的 zip（`Content-Disposition: attachment`）。
- 实现：遍历知识库文档，读取文件内容写入 zip（跳过已删除文档）。
- 前端：知识库页「导出」按钮下载 zip。

## 验证结果

| 功能 | 实测 |
| --- | --- |
| 批量导入 | 2 个文件 `{total:2, failed:0}`，重复文件标记 duplicate |
| 在线预览 | 返回 RAG 检索专题完整内容（1423 字） |
| 版本对比 | 更新文档后 `currentVersion=2`；对比 v1 与当前：from=343 字、to=380 字，识别新增内容 |
| 知识库导出 | zip 5KB，含 4 个文档文件（运维容量专题/RAG检索专题/metric-version/更新后文档） |

- 后端 51/51、前端 21/21 测试通过。

## 说明

- 更新文档时使用上传文件的原始文件名，因此新版本文件名可能变化（如用临时文件名更新会替换显示名），属既有行为。
