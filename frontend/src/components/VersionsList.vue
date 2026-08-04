<script setup>
import { ref, onMounted } from 'vue';
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';
import { closeModal } from '@/stores/modal';
import { session } from '@/stores/session';

const props = defineProps({
  documentId: { type: Number, required: true }
});

const versions = ref([]);
const currentVersion = ref(0);
const error = ref('');
const compareFrom = ref(null);
const compareTo = ref(null);
const compareResult = ref(null);
const comparing = ref(false);

async function load() {
  try {
    const data = await api(`/api/documents/${props.documentId}/versions`);
    versions.value = data.items || [];
    currentVersion.value = data.currentVersion || 0;
  } catch (err) {
    error.value = err.message;
    showToast(err.message, true);
  }
}

async function compare() {
  const from = Number(compareFrom.value);
  const to = Number(compareTo.value);
  if (!from || !to) {
    showToast('请选择两个版本', true);
    return;
  }
  if (from === to) {
    showToast('请选择两个不同版本', true);
    return;
  }
  comparing.value = true;
  compareResult.value = null;
  try {
    compareResult.value = await api(
      `/api/documents/${props.documentId}/compare?from=${from}&to=${to}`
    );
  } catch (err) {
    showToast(err.message, true);
  } finally {
    comparing.value = false;
  }
}

async function rollback(version) {
  if (!window.confirm(`确认回滚到版本 ${version}？`)) return;
  try {
    await api(`/api/documents/${props.documentId}/rollback/${version}`, {
      method: 'POST',
      body: '{}'
    });
    closeModal();
    showToast('已开始回滚');
    session.requestReload();
  } catch (err) {
    showToast(err.message, true);
  }
}

onMounted(load);
</script>

<template>
  <div v-if="error" class="empty">{{ error }}</div>
  <div v-else-if="!versions.length" class="empty small">暂无历史版本</div>
  <div v-else>
    <div class="compare-bar">
      <select v-model="compareFrom">
        <option value="">对比起始版本…</option>
        <option v-for="v in versions" :key="'f' + v.version" :value="v.version">v{{ v.version }}</option>
      </select>
      <span>→</span>
      <select v-model="compareTo">
        <option value="">对比目标版本…</option>
        <option v-if="currentVersion" :value="currentVersion">当前 v{{ currentVersion }}</option>
        <option v-for="v in versions" :key="'t' + v.version" :value="v.version">v{{ v.version }}</option>
      </select>
      <button class="secondary" :disabled="comparing" @click="compare">
        {{ comparing ? '对比中…' : '对比' }}
      </button>
    </div>
    <div v-if="compareResult" class="compare-result">
      <div class="compare-col">
        <div class="col-head">v{{ compareResult.fromVersion }}</div>
        <pre>{{ compareResult.fromContent }}</pre>
      </div>
      <div class="compare-col">
        <div class="col-head">v{{ compareResult.toVersion }}</div>
        <pre>{{ compareResult.toContent }}</pre>
      </div>
    </div>
    <div v-for="version in versions" :key="version.version" class="version-row">
      <div class="head">
        <span>v{{ version.version }} {{ version.fileName }}</span>
        <span class="muted">{{ formatTime(version.createdTime) }}</span>
      </div>
      <button @click="rollback(version.version)">回滚到该版本</button>
    </div>
  </div>
</template>

<style scoped>
.version-row {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 8px;
}

.version-row .head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
  color: var(--accent);
  font-weight: 600;
}

.muted {
  color: var(--muted);
  font-weight: 400;
}

.compare-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.compare-result {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 12px;
}

.compare-col pre {
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 40vh;
  overflow: auto;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px;
  font-size: 12px;
  line-height: 1.5;
}

.col-head {
  font-weight: 600;
  color: var(--accent);
  margin-bottom: 4px;
}

@media (max-width: 700px) {
  .compare-result {
    grid-template-columns: 1fr;
  }
}
</style>
