<script setup>
import { ref, watch, onMounted } from 'vue';
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';

const summary = ref(null);
const list = ref([]);
const error = ref('');

async function loadUsage() {
  try {
    const [summaryData, listData] = await Promise.all([
      api('/api/model-usage/summary'),
      api('/api/model-usage?limit=20')
    ]);
    summary.value = summaryData;
    list.value = listData.items || [];
    error.value = '';
  } catch (err) {
    error.value = err.message;
    showToast(err.message, true);
  }
}

watch(() => session.reloadKey, loadUsage);
onMounted(loadUsage);
</script>

<template>
  <div class="usage-stack">
    <div class="panel">
      <div class="panel-header"><h2>用量汇总</h2></div>
      <div v-if="error" class="empty small">{{ error }}</div>
      <div v-else-if="summary" class="usage-cards">
        <div class="usage-card">调用次数<br><b>{{ summary.totalCalls }}</b></div>
        <div class="usage-card">输入 Token<br><b>{{ summary.promptTokens }}</b></div>
        <div class="usage-card">输出 Token<br><b>{{ summary.completionTokens }}</b></div>
        <div class="usage-card">估算费用<br><b>${{ summary.estimatedCost }}</b></div>
      </div>
      <div v-else class="empty small">加载中…</div>
    </div>
    <div class="panel">
      <div class="panel-header"><h2>最近调用</h2></div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>场景</th><th>模型</th><th>输入</th><th>输出</th><th>费用</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="item in list" :key="item.id">
              <td>{{ item.scene }}</td>
              <td>{{ item.model }}</td>
              <td>{{ item.promptTokens }}</td>
              <td>{{ item.completionTokens }}</td>
              <td>{{ item.estimatedCost }}</td>
              <td>{{ formatTime(item.createdTime) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.usage-stack {
  display: grid;
  gap: 16px;
}
</style>
