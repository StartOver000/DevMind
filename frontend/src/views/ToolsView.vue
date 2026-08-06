<script setup>
import { ref, computed, onMounted } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';

// 登记表单
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

onMounted(load);
</script>

<template>
  <section class="tools-grid">
    <div class="panel">
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

    <div class="panel">
      <h2>已登记接口工具（{{ tools.length }}）</h2>
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!tools.length" class="empty">还没有登记接口。左侧登记一个内部接口，AI 就能调用它干活。</div>
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
                <button class="small danger" @click="deleteTool(t)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
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

@media (max-width: 900px) {
  .tools-grid {
    grid-template-columns: 1fr;
  }
}
</style>
