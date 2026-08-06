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
// 明细列表页签：recent | tools | workflows
const activeTab = ref('recent');

const tabs = [
  { key: 'recent', label: '最近调用' },
  { key: 'tools', label: '工具调用（近 7 天）' },
  { key: 'workflows', label: '工作流运行（近 7 天）' }
];

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
    <div class="panel detail-panel">
      <div class="tab-bar">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="tab-btn"
          :class="{ active: activeTab === t.key }"
          @click="activeTab = t.key"
        >{{ t.label }}</button>
      </div>

      <!-- 最近调用 -->
      <div v-if="activeTab === 'recent'" class="table-wrap">
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
        <div v-if="!list.length" class="empty small">暂无调用记录</div>
      </div>

      <!-- 工具调用 -->
      <div v-else-if="activeTab === 'tools'" class="table-wrap">
        <div v-if="!toolStats.length" class="empty small">暂无工具调用记录</div>
        <table v-else>
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

      <!-- 工作流运行 -->
      <div v-else class="table-wrap">
        <div v-if="!workflowStats.length" class="empty small">暂无工作流运行记录</div>
        <table v-else>
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
</template>

<style scoped>
.usage-stack {
  display: grid;
  gap: 16px;
}

/* 明细面板：列表头切换 + 内容多时面板内滚动，不撑高页面 */
.detail-panel {
  height: calc(100vh - 190px);
  min-height: 260px;
  overflow-y: auto;
  display: grid;
  align-content: start;
  gap: 10px;
}

.tab-bar {
  display: flex;
  gap: 6px;
  border-bottom: 1px solid var(--line);
  padding-bottom: 8px;
  flex-wrap: wrap;
}

.tab-btn {
  background: none;
  border: 1px solid var(--line);
  padding: 6px 14px;
  border-radius: 6px;
  color: var(--muted);
  font-size: 13px;
}

.tab-btn.active {
  background: var(--accent-weak);
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}

@media (max-width: 900px) {
  .detail-panel {
    height: auto;
    max-height: 420px;
    overflow-y: auto;
  }
}
</style>
