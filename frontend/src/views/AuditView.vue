<script setup>
import { ref, watch, onMounted } from 'vue';
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';

// 审计日志（产品运营盲区修复：审计有后端无前端，toB 信任卖点产品化）
// 数据源：/api/admin/audit/*（tool_call_log，由 ToolRegistry 统一写入）
const stats = ref([]);
const logs = ref([]);
const error = ref('');
const loading = ref(false);
const activeTab = ref('logs');

const tabs = [
  { key: 'logs', label: '调用明细' },
  { key: 'stats', label: '按工具统计（近 7 天）' }
];

const statusLabel = (s) => (s === 'success' ? '成功' : s === 'fail' ? '失败' : s || '-');
const sourceLabel = (s) => (s === 'agent' ? 'Agent' : s === 'workflow' ? '工作流' : s || '-');
const toolTypeLabel = (s) => (s === 'kb_search' ? '知识库检索' : s === 'ai_generate' ? 'AI 生成' : s || '-');

async function loadAudit() {
  loading.value = true;
  error.value = '';
  try {
    const [statsData, logsData] = await Promise.all([
      api('/api/admin/audit/tools?days=7'),
      api('/api/admin/audit/tools/logs?days=7&limit=100')
    ]);
    stats.value = statsData || [];
    logs.value = logsData || [];
  } catch (err) {
    error.value = err.message;
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

watch(() => session.reloadKey, loadAudit);
onMounted(loadAudit);
</script>

<template>
  <div class="audit-stack">
    <div class="panel">
      <div class="panel-header">
        <h2>审计日志</h2>
        <span class="audit-hint">管理员可见 · 记录 Agent / 工作流每次工具调用的轨迹（谁、何时、调用什么、成败）</span>
      </div>
      <div v-if="loading" class="empty small">加载中…</div>
      <div v-else-if="error" class="empty small">{{ error }}（仅管理员可查看）</div>
      <template v-else>
        <div class="tab-bar">
          <button
            v-for="t in tabs"
            :key="t.key"
            class="tab-btn"
            :class="{ active: activeTab === t.key }"
            @click="activeTab = t.key"
          >{{ t.label }}</button>
        </div>

        <!-- 调用明细 -->
        <div v-if="activeTab === 'logs'" class="table-wrap">
          <div v-if="!logs.length" class="empty small">暂无工具调用记录</div>
          <table v-else>
            <thead>
              <tr><th>时间</th><th>用户</th><th>工具</th><th>类型</th><th>来源</th><th>状态</th><th>耗时</th><th>错误</th></tr>
            </thead>
            <tbody>
              <tr v-for="log in logs" :key="log.id">
                <td>{{ formatTime(log.created_time) }}</td>
                <td>#{{ log.user_id }}</td>
                <td><b>{{ log.tool_name }}</b></td>
                <td>{{ toolTypeLabel(log.tool_type) }}</td>
                <td>{{ sourceLabel(log.source) }}</td>
                <td>
                  <span class="status-badge" :class="log.status === 'success' ? 'ok' : 'fail'">
                    {{ statusLabel(log.status) }}
                  </span>
                </td>
                <td>{{ log.cost_ms }}ms</td>
                <td class="err-cell" :title="log.error">{{ log.error || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 按工具统计 -->
        <div v-else class="table-wrap">
          <div v-if="!stats.length" class="empty small">暂无工具调用统计</div>
          <table v-else>
            <thead>
              <tr><th>工具</th><th>类型</th><th>次数</th><th>成功</th><th>失败</th><th>平均耗时(ms)</th><th>最后调用</th></tr>
            </thead>
            <tbody>
              <tr v-for="(s, i) in stats" :key="i">
                <td><b>{{ s.tool_name }}</b></td>
                <td>{{ toolTypeLabel(s.tool_type) }}</td>
                <td>{{ s.total }}</td>
                <td>{{ s.success_count }}</td>
                <td>{{ s.fail_count }}</td>
                <td>{{ s.avg_cost_ms }}</td>
                <td>{{ formatTime(s.last_time) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.audit-stack {
  display: grid;
  gap: 16px;
}

.audit-hint {
  color: var(--muted);
  font-size: 12px;
}

.table-wrap {
  overflow-x: auto;
}

.err-cell {
  max-width: 260px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--danger);
}

.status-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
}

.status-badge.ok {
  background: color-mix(in srgb, var(--success, #2e9e5b) 15%, transparent);
  color: var(--success, #2e9e5b);
}

.status-badge.fail {
  background: color-mix(in srgb, var(--danger) 15%, transparent);
  color: var(--danger);
}
</style>
