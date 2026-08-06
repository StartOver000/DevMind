<script setup>
import { ref, onMounted } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';

// 对话式创建
const description = ref('');
const generating = ref(false);
const draft = ref(null); // { steps, stepsJson }
const workflowName = ref('');
const triggerType = ref('manual');
const cronExpr = ref('');
const creating = ref(false);

// 工作流列表
const workflows = ref([]);
const loading = ref(false);
const runningId = ref(null);
const runResult = ref(null);

// 运行记录
const runs = ref([]);
const runsFor = ref(null);
const runDetail = ref(null);
const detailFor = ref(null);
const loadingRuns = ref(false);

async function load() {
  loading.value = true;
  try {
    workflows.value = await api('/api/workflows');
    await loadWebhookUrls();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

// webhook 工作流的调用 URL（token 单独查询）
const webhookUrls = ref({});
const origin = location.origin;

async function loadWebhookUrls() {
  const hooks = workflows.value.filter(w => w.triggerType === 'webhook');
  webhookUrls.value = {};
  for (const w of hooks) {
    try {
      const info = await api(`/api/workflows/${w.id}/webhook`);
      if (info.url) webhookUrls.value[w.id] = info.url;
    } catch (err) {
      // 忽略单条查询失败
    }
  }
}

async function copyWebhookUrl(id) {
  const url = webhookUrls.value[id];
  if (!url) return;
  try {
    await navigator.clipboard.writeText(location.origin + url);
    showToast('Webhook 地址已复制');
  } catch (err) {
    showToast('复制失败，请手动复制：' + url, true);
  }
}

async function generateDraft() {
  if (!description.value.trim()) {
    showToast('请描述你的需求', true);
    return;
  }
  generating.value = true;
  draft.value = null;
  runResult.value = null;
  try {
    draft.value = await api('/api/workflows/generate', {
      method: 'POST',
      body: JSON.stringify({ description: description.value.trim() })
    });
  } catch (err) {
    showToast(err.message, true);
  } finally {
    generating.value = false;
  }
}

async function createWorkflow() {
  if (!draft.value) return;
  const name = workflowName.value.trim() || description.value.trim().slice(0, 30);
  creating.value = true;
  try {
    const w = await api('/api/workflows', {
      method: 'POST',
      body: JSON.stringify({
        name,
        description: description.value.trim(),
        stepsJson: draft.value.stepsJson,
        triggerType: triggerType.value,
        cronExpr: triggerType.value === 'cron' ? cronExpr.value.trim() : undefined
      })
    });
    showToast(`工作流 ${w.name} 已创建`);
    draft.value = null;
    workflowName.value = '';
    description.value = '';
    cronExpr.value = '';
    triggerType.value = 'manual';
    await load();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    creating.value = false;
  }
}

async function runWorkflow(w) {
  if (!confirm(`确认运行工作流「${w.name}」？`)) return;
  runningId.value = w.id;
  runResult.value = null;
  try {
    const run = await api(`/api/workflows/${w.id}/run`, { method: 'POST' });
    runResult.value = { name: w.name, run };
    if (run.status === 'SUCCESS') {
      // 拉取步骤详情拿最终结果
      try {
        const detail = await api(`/api/workflows/runs/${run.id}`);
        const last = detail.steps[detail.steps.length - 1];
        runResult.value.resultText = last && last.outputJson ? last.outputJson : '';
      } catch (e) { /* 忽略 */ }
      showToast('运行成功');
    } else {
      showToast(`运行失败：${run.error || '未知错误'}`, true);
    }
  } catch (err) {
    showToast(err.message, true);
  } finally {
    runningId.value = null;
  }
}

async function showRuns(w) {
  runsFor.value = w.id;
  detailFor.value = null;
  runDetail.value = null;
  loadingRuns.value = true;
  runs.value = [];
  try {
    runs.value = await api(`/api/workflows/${w.id}/runs?limit=10`);
  } catch (err) {
    showToast(err.message, true);
  } finally {
    loadingRuns.value = false;
  }
}

async function showDetail(runId) {
  try {
    runDetail.value = await api(`/api/workflows/runs/${runId}`);
    detailFor.value = runId;
  } catch (err) {
    showToast(err.message, true);
  }
}

async function deleteWorkflow(w) {
  if (!confirm(`确认删除工作流「${w.name}」？`)) return;
  try {
    await api(`/api/workflows/${w.id}`, { method: 'DELETE' });
    showToast('工作流已删除');
    await load();
  } catch (err) {
    showToast(err.message, true);
  }
}

onMounted(load);
</script>

<template>
  <section class="wf-grid">
    <div class="panel">
      <h2>创建流程（说人话）</h2>
      <label>描述需求
        <textarea v-model="description" rows="3" placeholder="例如：查一下监控系统的版本信息，然后用一句话总结运行状态"></textarea>
      </label>
      <button class="primary" :disabled="generating" @click="generateDraft">
        {{ generating ? '生成中…' : '生成流程草案' }}
      </button>

      <div v-if="draft" class="draft">
        <h3>草案（{{ draft.steps.length }} 步）</h3>
        <div v-for="(s, i) in draft.steps" :key="i" class="draft-step">
          <span class="no">{{ i + 1 }}</span>
          <div class="body">
            <div><b>{{ s.tool }}</b><span v-if="s.goal" class="goal">—— {{ s.goal }}</span></div>
            <div v-if="s.paramsJson && s.paramsJson !== '{}'" class="params">{{ s.paramsJson }}</div>
            <div v-if="s.outputVar" class="out">→ {{ s.outputVar }}</div>
          </div>
        </div>
        <label>流程名称（可选）
          <input v-model="workflowName" placeholder="留空则用需求前 30 字">
        </label>
        <label>触发方式
          <select v-model="triggerType">
            <option value="manual">手动运行</option>
            <option value="cron">定时运行</option>
            <option value="webhook">Webhook 调用</option>
          </select>
        </label>
        <label v-if="triggerType === 'cron'">cron 表达式
          <input v-model="cronExpr" placeholder="例如：0 0 9 * * *（每天 09:00）">
        </label>
        <p v-else-if="triggerType === 'webhook'" class="hint">创建后将生成调用地址，外部系统 POST 即可触发此工作流。</p>
        <button class="primary" :disabled="creating" @click="createWorkflow">
          {{ creating ? '创建中…' : '确认创建流程' }}
        </button>
      </div>
      <div v-if="runResult" class="run-result" :class="runResult.run.status.toLowerCase()">
        <h3>运行结果：{{ runResult.run.status }}</h3>
        <p v-if="runResult.run.error">错误：{{ runResult.run.error }}</p>
        <pre v-if="runResult.resultText">{{ runResult.resultText }}</pre>
      </div>
    </div>

    <div class="panel">
      <h2>我的流程（{{ workflows.length }}）</h2>
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!workflows.length" class="empty">还没有流程。左侧描述需求，AI 帮你生成。</div>
      <template v-else>
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>名称</th><th>描述</th><th>触发</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="w in workflows" :key="w.id">
                <td><b>{{ w.name }}</b></td>
                <td class="desc">{{ w.description || '—' }}</td>
                <td class="trigger">
                {{ w.triggerType === 'cron' ? '定时 ' + (w.cronExpr || '') : (w.triggerType === 'webhook' ? 'Webhook' : '手动') }}
                <span v-if="w.triggerType === 'webhook' && webhookUrls[w.id]" class="webhook-url" :title="origin + webhookUrls[w.id]">
                  {{ webhookUrls[w.id] }}
                  <button class="small" @click.stop="copyWebhookUrl(w.id)">复制</button>
                </span>
              </td>
                <td><span class="status" :class="w.status">{{ w.status }}</span></td>
                <td>
                  <button class="small" :disabled="runningId === w.id || w.status !== 'ENABLED'" @click="runWorkflow(w)">{{ runningId === w.id ? '运行中…' : '运行' }}</button>
                  <button class="small" @click="showRuns(w)">记录</button>
                  <button class="small danger" @click="deleteWorkflow(w)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="runsFor !== null" class="runs">
          <h3>运行记录</h3>
          <div v-if="loadingRuns" class="empty">加载中…</div>
          <div v-else-if="!runs.length" class="empty">暂无运行记录</div>
          <div v-else class="table-wrap">
            <table>
              <thead><tr><th>#</th><th>状态</th><th>触发</th><th>错误</th><th></th></tr></thead>
              <tbody>
                <tr v-for="r in runs" :key="r.id">
                  <td>{{ r.id }}</td>
                  <td><span class="status" :class="r.status">{{ r.status }}</span></td>
                  <td>{{ r.triggerType }}</td>
                  <td class="err">{{ r.error || '—' }}</td>
                  <td><button class="small" @click="showDetail(r.id)">步骤</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="runDetail && detailFor" class="runs">
          <h3>运行 #{{ detailFor }} 步骤明细</h3>
          <div v-for="(s, i) in runDetail.steps" :key="i" class="step-row" :class="s.status.toLowerCase()">
            <div class="head">
              <span class="no">{{ i + 1 }}</span>
              <b>{{ s.toolName }}</b>
              <span class="status" :class="s.status">{{ s.status }}</span>
              <span class="cost">{{ s.costMs }}ms</span>
            </div>
            <div v-if="s.error" class="err">错误：{{ s.error }}</div>
            <div v-if="s.outputJson" class="out">{{ s.outputJson }}</div>
          </div>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.wf-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 400px 1fr;
  align-items: start;
}

.wf-grid .panel {
  display: grid;
  gap: 10px;
}

.draft {
  border-top: 1px dashed var(--line);
  padding-top: 12px;
  display: grid;
  gap: 8px;
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--muted, #888);
}

.webhook-url {
  display: block;
  font-size: 11px;
  color: var(--muted, #888);
  font-family: var(--mono, monospace);
  word-break: break-all;
  margin-top: 2px;
}

.draft-step {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 8px;
}

.no {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.draft-step .body {
  font-size: 13px;
  display: grid;
  gap: 2px;
}

.draft-step .params, .draft-step .out {
  color: var(--muted);
  font-size: 12px;
  font-family: var(--mono, monospace);
  word-break: break-all;
}

.run-result {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 8px 10px;
  background: var(--alt-bg);
}

.run-result pre {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 4px 0 0;
  font-size: 13px;
}

td.desc {
  max-width: 220px;
}

td.err {
  color: var(--danger);
  max-width: 200px;
}

.runs {
  border-top: 1px dashed var(--line);
  margin-top: 10px;
  padding-top: 10px;
  display: grid;
  gap: 6px;
}

.step-row {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 8px;
  display: grid;
  gap: 4px;
}

.step-row .head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.step-row .out {
  font-size: 12px;
  color: var(--muted);
  font-family: var(--mono, monospace);
  word-break: break-word;
  max-height: 120px;
  overflow-y: auto;
}

.cost {
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
}

button.danger {
  color: var(--danger);
  border-color: var(--danger);
}

@media (max-width: 900px) {
  .wf-grid {
    grid-template-columns: 1fr;
  }
}
</style>
