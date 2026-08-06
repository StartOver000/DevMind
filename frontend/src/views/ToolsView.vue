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
    </div>
    <div v-else class="panel hint-panel">
      <p class="hint">你是团队成员，无法登记接口工具。下方展示的是管理员已授权给你的接口。</p>
      <p class="hint sub">需要更多工具？联系管理员在「授权管理」中分配。</p>
    </div>

    <div class="panel scroll-panel">
      <div class="list-head">
        <h2>{{ isAdmin ? '已登记接口工具' : '我可用接口工具' }}（{{ tools.length }}）</h2>
        <div v-if="isAdmin" class="grant-entry">
          <span>授权管理：</span>
          <select v-if="users.length" v-model="grantUser" @change="openGrant(grantUser)">
            <option :value="null" disabled>选择成员…</option>
            <option v-for="u in users" :key="u.id" :value="u">{{ u.displayName || u.username }}</option>
          </select>
        </div>
      </div>
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!tools.length" class="empty">
        {{ isAdmin ? '还没有登记接口。左侧登记一个内部接口，AI 就能调用它干活。' : '管理员还没有授权接口给你。' }}
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>名称</th><th>描述</th><th>地址</th><th>方法</th><th>鉴权</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="t in tools" :key="t.id">
              <td><b>{{ t.name }}</b></td>
              <td class="desc">{{ t.description || '—' }}</td>
              <td class="url">{{ t.endpointUrl }}</td>
              <td>{{ t.httpMethod }}</td>
              <td>{{ t.authType }}</td>
              <td>
                <button class="small" :disabled="testingId === t.id" @click="testTool(t.id)">{{ testingId === t.id ? '测试中…' : '连通测试' }}</button>
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
