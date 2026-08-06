<script setup>
import { ref, watch, onMounted } from 'vue';
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';

const summary = ref(null);
const list = ref([]);
const toolStats = ref([]);
const workflowStats = ref([]);
const error = ref('');

async function loadUsage() {
  try {
    const [summaryData, listData, toolData, wfData] = await Promise.all([
      api('/api/model-usage/summary'),
      api('/api/model-usage?limit=20'),
      api('/api/usage/tools?days=7'),
      api('/api/usage/workflows?days=7')
    ]);
    summary.value = summaryData;
    list.value = listData.items || [];
    toolStats.value = toolData || [];
    workflowStats.value = wfData || [];
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
    <div class="usage-cols">
    <div class="panel scroll-panel">
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
    <div class="panel scroll-panel">
      <div class="panel-header"><h2>工具调用（近 7 天）</h2></div>
      <div v-if="!toolStats.length" class="empty small">暂无工具调用记录</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>工具</th><th>类型</th><th>次数</th><th>成功</th><th>失败</th><th>平均耗时(ms)</th></tr>
          </thead>
          <tbody>
            <tr v-for="(s, i) in toolStats" :key="i">
              <td><b>{{ s.tool_name }}</b></td>
              <td>{{ s.tool_type }}</td>
              <td>{{ s.total }}</td>
              <td>{{ s.success_count }}</td>
              <td>{{ s.fail_count }}</td>
              <td>{{ s.avg_cost_ms }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="panel scroll-panel">
      <div class="panel-header"><h2>工作流运行（近 7 天）</h2></div>
      <div v-if="!workflowStats.length" class="empty small">暂无工作流运行记录</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>工作流</th><th>次数</th><th>成功</th><th>失败</th><th>费用</th></tr>
          </thead>
          <tbody>
            <tr v-for="(s, i) in workflowStats" :key="i">
              <td><b>{{ s.workflow_name }}</b></td>
              <td>{{ s.total }}</td>
              <td>{{ s.success_count }}</td>
              <td>{{ s.fail_count }}</td>
              <td>{{ Number(s.total_cost).toFixed(6) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    </div>
  </div>
</template>

<style scoped>
.usage-stack {
  display: grid;
  gap: 16px;
}

/* 三个统计表格并排，内容多时列内滚动，一屏展示 */
.usage-cols {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  align-items: start;
}

/* 表格面板：内容多时面板内滚动，不撑高整个页面 */
.usage-cols .scroll-panel {
  height: calc(100vh - 190px);
  min-height: 240px;
  overflow-y: auto;
}

@media (max-width: 1100px) {
  .usage-cols {
    grid-template-columns: 1fr;
  }
  .usage-cols .scroll-panel {
    height: auto;
    max-height: 360px;
    overflow-y: auto;
  }
}
</style>
