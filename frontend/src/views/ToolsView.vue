<script setup>
import { ref, computed, onMounted } from 'vue';
import { api, getCurrentUserId } from '@/api/client';
import { showToast } from '@/stores/toast';

// 当前用户与角色
const me = ref(null);
const isAdmin = computed(() => me.value && me.value.role === 'ADMIN');

// 登记表单（仅管理员可见）
const form = ref({
  name: '',
  description: '',
  endpointUrl: '',
  httpMethod: 'GET',
  requestSchemaJson: '',
  authType: 'none',
  authConfig: '',
  maskFieldsJson: ''
});
const submitting = ref(false);

// 工具列表
const tools = ref([]);
const loading = ref(false);
const testingId = ref(null);

// OpenAPI 批量导入（仅管理员）
const importFile = ref(null);
const importing = ref(false);
const importResult = ref(null);

// 语义检索（所有用户）
const searchQuery = ref('');
const searchResults = ref(null); // null=未搜索，展示全部工具
const searching = ref(false);
const searchError = ref('');

// AI 语义增强（仅管理员）
const enhancingId = ref(null);

// 授权管理（仅管理员）：选择成员 → 勾选可用工具
const users = ref([]);
const grantOpen = ref(false);
const grantUser = ref(null);
const grantedIds = ref(new Set());
const grantBusy = ref(false);

// 鉴权配置占位提示（避免模板内转义问题，放 script 计算）
const authPlaceholder = computed(() =>
  form.value.authType === 'api_key'
    ? '{"location":"header","key":"X-API-Key","value":"密钥"}'
    : '{"username":"用户","password":"密码"}'
);

async function load() {
  loading.value = true;
  try {
    tools.value = await api('/api/tools');
  } catch (err) {
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

async function loadMe() {
  try {
    me.value = await api('/api/users/me');
    if (isAdmin.value) {
      users.value = (await api('/api/users')).items || [];
    }
  } catch (err) {
    // 后端无 /me 时静默降级：按管理员处理
    me.value = { role: 'ADMIN', id: getCurrentUserId() };
  }
}

async function createTool() {
  if (!form.value.name.trim() || !form.value.endpointUrl.trim()) {
    showToast('请填写工具名和接口地址', true);
    return;
  }
  // JSON 字段合法性校验（填写时必须是合法 JSON，避免登记了无法解析的工具）
  for (const [key, label] of [['requestSchemaJson', '请求参数 Schema'], ['authConfig', '鉴权配置'], ['maskFieldsJson', '脱敏字段']]) {
    const raw = (form.value[key] || '').trim();
    if (!raw) continue;
    try {
      JSON.parse(raw);
    } catch (e) {
      showToast(`${label} 不是合法 JSON，请检查格式`, true);
      return;
    }
  }
  submitting.value = true;
  try {
    const payload = {
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      endpointUrl: form.value.endpointUrl.trim(),
      httpMethod: form.value.httpMethod,
      requestSchemaJson: form.value.requestSchemaJson.trim() || undefined,
      authType: form.value.authType,
      authConfig: form.value.authConfig.trim() || undefined,
      maskFieldsJson: form.value.maskFieldsJson.trim() || undefined
    };
    const created = await api('/api/tools', { method: 'POST', body: JSON.stringify(payload) });
    showToast(`接口工具 ${created.name} 已登记`);
    form.value = { name: '', description: '', endpointUrl: '', httpMethod: 'GET', requestSchemaJson: '', authType: 'none', authConfig: '', maskFieldsJson: '' };
    await load();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    submitting.value = false;
  }
}

async function testTool(id) {
  testingId.value = id;
  try {
    const res = await api(`/api/tools/${id}/test`, { method: 'POST' });
    showToast(res.message, !res.ok);
  } catch (err) {
    showToast(err.message, true);
  } finally {
    testingId.value = null;
  }
}

async function deleteTool(tool) {
  if (!confirm(`确认删除接口工具 ${tool.name}？`)) return;
  try {
    await api(`/api/tools/${tool.id}`, { method: 'DELETE' });
    showToast('工具已删除');
    await load();
  } catch (err) {
    showToast(err.message, true);
  }
}

// ---------- OpenAPI 批量导入（管理员）----------
function onImportFile(e) {
  importFile.value = e.target.files[0] || null;
  importResult.value = null;
}

async function importOpenApi() {
  if (!importFile.value) {
    showToast('请先选择 OpenAPI 3.0 文档（JSON 或 YAML）', true);
    return;
  }
  importing.value = true;
  importResult.value = null;
  try {
    const fd = new FormData();
    fd.append('file', importFile.value);
    const res = await api('/api/tools/import', { method: 'POST', body: fd });
    importResult.value = res;
    const verb = res.created > 0 ? `导入 ${res.created} 个接口` : '无新接口'; 
    showToast(`『${res.docTitle || 'OpenAPI'}』${verb}${res.skipped ? `，跳过 ${res.skipped} 个已存在` : ''}${res.failed ? `，失败 ${res.failed} 个` : ''}`);
    importFile.value = null;
    await load();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    importing.value = false;
  }
}

// ---------- 语义检索（自然语言 → 命中接口）----------
async function doSearch() {
  const q = searchQuery.value.trim();
  if (!q) {
    clearSearch();
    return;
  }
  searching.value = true;
  searchError.value = '';
  try {
    searchResults.value = await api(`/api/tools/search?q=${encodeURIComponent(q)}&limit=8`);
  } catch (err) {
    searchError.value = err.message;
    searchResults.value = [];
  } finally {
    searching.value = false;
  }
}

function clearSearch() {
  searchQuery.value = '';
  searchResults.value = null;
  searchError.value = '';
}

// ---------- AI 语义增强（管理员）----------
async function enhanceSemantic(tool) {
  enhancingId.value = tool.id;
  try {
    const res = await api(`/api/tools/${tool.id}/semantic`, { method: 'POST' });
    // 语义文本较长，toast 只展示 AI 增强段落
    const aiPart = (res.semanticText || '').split('【AI 增强】')[1] || '';
    showToast(`『${tool.name}』已增强语义：${aiPart.trim().split('\n')[0]}`);
  } catch (err) {
    showToast(err.message, true);
  } finally {
    enhancingId.value = null;
  }
}

// ---------- 授权管理 ----------
async function openGrant(user) {
  grantUser.value = user;
  grantOpen.value = true;
  try {
    const res = await api(`/api/admin/tools/grants/${user.id}`);
    grantedIds.value = new Set(res.toolIds || []);
  } catch (err) {
    showToast(err.message, true);
    grantedIds.value = new Set();
  }
}

async function toggleGrant(tool) {
  grantBusy.value = true;
  try {
    if (grantedIds.value.has(tool.id)) {
      await api(`/api/admin/tools/${tool.id}/grants?subjectType=user&subjectId=${grantUser.value.id}`, { method: 'DELETE' });
      grantedIds.value.delete(tool.id);
      showToast(`已撤销 ${tool.name} 的授权`);
    } else {
      await api(`/api/admin/tools/${tool.id}/grants`, {
        method: 'POST',
        body: JSON.stringify({ subjectType: 'user', subjectId: grantUser.value.id })
      });
      grantedIds.value.add(tool.id);
      showToast(`已授权 ${tool.name}`);
    }
    await load();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    grantBusy.value = false;
  }
}

onMounted(() => {
  loadMe();
  load();
});
</script>

<template>
  <section class="tools-grid">
    <div class="panel" v-if="isAdmin">
      <h2>登记接口（生成 AI 可调用工具）</h2>
      <label>工具名（字母/数字/下划线）
        <input v-model="form.name" placeholder="例如：customer_query">
      </label>
      <label>描述（AI 判断何时调用）
        <input v-model="form.description" placeholder="例如：查询 CRM 客户列表（按日期筛选新客户）">
      </label>
      <label>接口地址
        <input v-model="form.endpointUrl" placeholder="例如：http://crm.internal/api/customers">
      </label>
      <label>请求方法
        <select v-model="form.httpMethod">
          <option v-for="m in ['GET','POST','PUT','DELETE']" :key="m" :value="m">{{ m }}</option>
        </select>
      </label>
      <label>参数 Schema（JSON，可选）
        <textarea v-model="form.requestSchemaJson" rows="2" placeholder='{"type":"object","properties":{"days":{"type":"integer"}},"required":["days"]}'></textarea>
      </label>
      <label>鉴权类型
        <select v-model="form.authType">
          <option value="none">无</option>
          <option value="api_key">API Key</option>
          <option value="basic">Basic 账号密码</option>
        </select>
      </label>
      <label v-if="form.authType !== 'none'">鉴权配置（JSON）
        <textarea v-model="form.authConfig" rows="2" :placeholder="authPlaceholder"></textarea>
      </label>
      <label>脱敏字段（JSON 数组，可选）
        <input v-model="form.maskFieldsJson" placeholder='例如：["phone","idCard"]'>
      </label>
      <button class="primary" :disabled="submitting" @click="createTool">
        {{ submitting ? '登记中…' : '登记接口' }}
      </button>

      <div class="import-divider">或批量导入 OpenAPI</div>
      <label class="file-label">OpenAPI 3.0 文档（JSON / YAML）
        <input type="file" accept=".json,.yaml,.yml" @change="onImportFile">
      </label>
      <button class="primary ghost" :disabled="importing || !importFile" @click="importOpenApi">
        {{ importing ? '导入中…' : '导入并生成接口工具' }}
      </button>
      <div v-if="importResult" class="import-result">
        <p class="ok">『{{ importResult.docTitle || 'OpenAPI' }}』共 {{ importResult.total }} 个接口：
          <b class="ok">新建 {{ importResult.created }}</b>，
          <span v-if="importResult.skipped">跳过 {{ importResult.skipped }}</span>
          <span v-if="importResult.failed" class="danger-text">失败 {{ importResult.failed }}</span>
        </p>
        <p class="hint sub">导入的接口已生成语义档案（向量化），可在右侧搜索框用自然语言检索。</p>
      </div>
    </div>
    <div v-else class="panel hint-panel">
      <p class="hint">你是团队成员，无法登记接口工具。下方展示的是管理员已授权给你的接口。</p>
      <p class="hint sub">需要更多工具？联系管理员在「授权管理」中分配。</p>
    </div>

    <div class="panel scroll-panel">
      <div class="list-head">
        <h2>{{ searchResults ? `语义检索：${searchResults.length} 个命中` : (isAdmin ? '已登记接口工具' : '我可用接口工具') }}（{{ tools.length }}）</h2>
        <div class="search-entry">
          <input
            v-model="searchQuery"
            placeholder="用自然语言找接口，如：查一下今天的订单"
            @keyup.enter="doSearch"
          >
          <button class="small" :disabled="searching || !searchQuery.trim()" @click="doSearch">{{ searching ? '检索中…' : '语义检索' }}</button>
          <button v-if="searchResults" class="small ghost" @click="clearSearch">清空</button>
        </div>
        <div v-if="isAdmin" class="grant-entry">
          <span>授权管理：</span>
          <select v-if="users.length" v-model="grantUser" @change="openGrant(grantUser)">
            <option :value="null" disabled>选择成员…</option>
            <option v-for="u in users" :key="u.id" :value="u">{{ u.displayName || u.username }}</option>
          </select>
        </div>
      </div>
      <div v-if="loading" class="skeleton-list" aria-label="加载中">
        <div v-for="i in 3" :key="i" class="skeleton-row">
          <div class="skeleton-block lg"></div>
          <div class="skeleton-block md"></div>
          <div class="skeleton-block sm"></div>
        </div>
      </div>
      <div v-else-if="!tools.length" class="empty">
        {{ isAdmin ? '还没有登记接口。左侧登记一个内部接口，AI 就能调用它干活。' : '管理员还没有授权接口给你。' }}
      </div>
      <div v-else-if="searchError" class="empty">检索失败：{{ searchError }}</div>
      <div v-else-if="searchResults && !searchResults.length" class="empty">
        没有命中「{{ searchQuery }}」的接口。换个说法试试，或让管理员在左侧用「AI 语义增强」丰富接口描述。
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>名称</th><th>描述</th><th>地址</th><th>方法</th><th>鉴权</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="t in (searchResults || tools)" :key="t.id">
              <td><b>{{ t.name }}</b></td>
              <td class="desc">{{ t.description || '—' }}</td>
              <td class="url">{{ t.endpointUrl }}</td>
              <td>{{ t.httpMethod }}</td>
              <td>{{ t.authType }}</td>
              <td>
                <button class="small" :disabled="testingId === t.id" @click="testTool(t.id)">{{ testingId === t.id ? '测试中…' : '连通测试' }}</button>
                <button v-if="isAdmin" class="small" :disabled="enhancingId === t.id" @click="enhanceSemantic(t)">{{ enhancingId === t.id ? '增强中…' : 'AI 语义' }}</button>
                <button v-if="isAdmin" class="small danger" @click="deleteTool(t)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>

  <!-- 授权弹窗：管理员给成员分配可用工具 -->
  <div v-if="grantOpen && grantUser" class="modal-mask" @click.self="grantOpen = false">
    <div class="modal">
      <h3>为 {{ grantUser.displayName || grantUser.username }} 分配工具</h3>
      <p class="modal-desc">勾选后该成员即可在对话、工作流中使用这些接口工具。</p>
      <div v-if="!tools.length" class="empty">暂无已登记工具</div>
      <div v-else class="grant-list">
        <label v-for="t in tools" :key="t.id" class="grant-item">
          <input
            type="checkbox"
            :checked="grantedIds.has(t.id)"
            :disabled="grantBusy"
            @change="toggleGrant(t)"
          >
          <span>
            <b>{{ t.name }}</b>
            <small>{{ t.description || t.endpointUrl }}</small>
          </span>
        </label>
      </div>
      <div class="modal-actions">
        <button class="small" @click="grantOpen = false">关闭</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tools-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 380px 1fr;
  align-items: start;
}

.tools-grid .panel {
  display: grid;
  gap: 10px;
}

/* 接口列表：内容多时面板内滚动，不撑高页面 */
.tools-grid .scroll-panel {
  height: calc(100vh - 110px);
  overflow-y: auto;
  align-content: start;
}

@media (max-width: 900px) {
  .tools-grid .scroll-panel {
    height: auto;
    max-height: 420px;
    overflow-y: auto;
  }
}

td.url {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--mono, monospace);
  font-size: 12px;
}

td.desc {
  max-width: 240px;
}

button.danger {
  color: var(--danger);
  border-color: var(--danger);
}

.hint-panel {
  align-content: start;
}

/* OpenAPI 导入区块 */
.import-divider {
  border-top: 1px dashed var(--border, #ddd);
  padding-top: 10px;
  color: var(--muted, #888);
  font-size: 12px;
  text-align: center;
}

.file-label {
  display: grid;
  gap: 4px;
  font-size: 13px;
  color: var(--muted, #666);
}

.file-label input[type='file'] {
  font-size: 12px;
  padding: 4px;
}

.import-result {
  background: var(--bg-soft, #f6f8fa);
  border: 1px solid var(--border, #ddd);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
}

.import-result .ok { color: var(--ok, #1a7f37); }
.danger-text { color: var(--danger, #d1242f); }

button.ghost {
  background: transparent;
  color: var(--accent, #0969da);
}

/* 语义检索条 */
.search-entry {
  display: flex;
  align-items: center;
  gap: 6px;
}

.search-entry input {
  width: 260px;
  padding: 6px 10px;
  border: 1px solid var(--border, #ddd);
  border-radius: 6px;
  font-size: 13px;
}

@media (max-width: 1100px) {
  .search-entry { flex-wrap: wrap; }
  .search-entry input { width: 100%; }
}

.hint-panel {
  align-content: start;
  padding: 24px;
  border: 1px dashed var(--border, #ccc);
  border-radius: 10px;
}
.hint {
  margin: 0;
  color: var(--muted, #888);
  line-height: 1.6;
}
.hint.sub {
  font-size: 13px;
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.grant-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--muted, #888);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.modal {
  background: var(--bg, #fff);
  border: 1px solid var(--border, #ddd);
  border-radius: 12px;
  padding: 20px;
  width: 440px;
  max-width: calc(100vw - 32px);
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.modal h3 {
  margin: 0;
}
.modal-desc {
  margin: 0;
  font-size: 13px;
  color: var(--muted, #888);
}
.grant-list {
  overflow-y: auto;
  display: grid;
  gap: 6px;
}
.grant-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--border, #eee);
  border-radius: 8px;
  cursor: pointer;
}
.grant-item input {
  margin-top: 3px;
}
.grant-item span {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.grant-item small {
  color: var(--muted, #888);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 900px) {
  .tools-grid {
    grid-template-columns: 1fr;
  }
}
</style>
