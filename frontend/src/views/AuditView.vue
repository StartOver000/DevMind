<script setup>
import { ref, watch, onMounted } from 'vue';
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';
import EvidenceDrawer from '@/components/EvidenceDrawer.vue';

// 审计日志（产品运营盲区修复：审计有后端无前端，toB 信任卖点产品化）
// 数据源：/api/admin/audit/*（tool_call_log，由 ToolRegistry 统一写入）
const stats = ref([]);
const logs = ref([]);
const error = ref('');
const loading = ref(false);
const activeTab = ref('logs');

// 操作回放（G2）：按用户 + 时间窗重建一次「操作轨迹」——每个工具调用的授权结论与命中策略都在里面
const replayUserId = ref(1);
const replayMinutes = ref(30);
const replay = ref(null);
const replayLoading = ref(false);

// 操作证据（G4/G5）：从回放行点进证据抽屉，看审批→执行的哈希链与校验结果
const evidenceOpen = ref(false);
const evidencePkg = ref(null);
const evidenceLoading = ref(false);

async function openEvidence(runId) {
  evidenceOpen.value = true;
  evidenceLoading.value = true;
  evidencePkg.value = null;
  try {
    evidencePkg.value = await api(`/api/audit/operations/${runId}`);
  } catch (err) {
    showToast(err.message, true);
    evidenceOpen.value = false;
  } finally {
    evidenceLoading.value = false;
  }
}

const tabs = [
  { key: 'logs', label: '调用明细' },
  { key: 'replay', label: '操作回放' },
  { key: 'stats', label: '按工具统计（近 7 天）' }
];

const decisionLabel = (d) => (d === 'DENY' ? '已拦截' : d === 'ALLOW' ? '已放行' : '-');

async function loadReplay() {
  replayLoading.value = true;
  replay.value = null;
  try {
    const q = `userId=${encodeURIComponent(replayUserId.value)}&minutes=${encodeURIComponent(replayMinutes.value)}`;
    replay.value = await api(`/api/admin/audit/tools/replay?${q}`);
  } catch (err) {
    showToast(err.message, true);
  } finally {
    replayLoading.value = false;
  }
}

const statusLabel = (s) =>
  s === 'success' ? '成功' : s === 'fail' ? '失败' : s === 'denied' ? '已拦截' : s || '-';
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
                  <span class="status-badge" :class="log.status === 'success' ? 'ok' : (log.status === 'denied' ? 'warn' : 'fail')">
                    {{ statusLabel(log.status) }}
                  </span>
                </td>
                <td>{{ log.cost_ms }}ms</td>
                <td class="err-cell" :title="log.error">{{ log.error || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 操作回放：一次操作的时间线 -->
        <div v-else-if="activeTab === 'replay'" class="replay-wrap">
          <div class="replay-form">
            <label>用户 ID <input v-model.number="replayUserId" type="number" min="1" /></label>
            <label>最近 <input v-model.number="replayMinutes" type="number" min="1" max="1440" /> 分钟</label>
            <button class="small" :disabled="replayLoading" @click="loadReplay">
              {{ replayLoading ? '回放中…' : '回放' }}
            </button>
          </div>

          <template v-if="replay">
            <div class="replay-summary">
              <span>共 <b>{{ replay.total }}</b> 次调用</span>
              <span :class="{ danger: replay.denied > 0 }">被拒绝 <b>{{ replay.denied }}</b> 次</span>
              <span>涉及工具：{{ (replay.tools || []).join('、') || '-' }}</span>
              <span v-if="replay.deniedPolicies && replay.deniedPolicies.length">
                命中策略：{{ replay.deniedPolicies.join('、') }}
              </span>
            </div>
            <div v-if="!replay.events || !replay.events.length" class="empty small">
              该用户最近 {{ replay.windowMinutes }} 分钟内没有工具调用
            </div>
            <table v-else>
              <thead>
                <tr><th>时间</th><th>工具</th><th>来源</th><th>授权</th><th>策略</th><th>参数摘要</th><th>状态</th><th>耗时</th><th>证据</th></tr>
              </thead>
              <tbody>
                <tr v-for="ev in replay.events" :key="ev.id" :class="{ denied: ev.decision === 'DENY' }">
                  <td>{{ formatTime(ev.created_time) }}</td>
                  <td><b>{{ ev.tool_name }}</b></td>
                  <td>{{ sourceLabel(ev.source) }}</td>
                  <td>
                    <span class="status-badge" :class="ev.decision === 'DENY' ? 'fail' : 'ok'">
                      {{ decisionLabel(ev.decision) }}
                    </span>
                  </td>
                  <td>{{ ev.policy_id || '-' }}</td>
                  <td class="hash-cell" :title="ev.args_hash">{{ ev.args_hash ? ev.args_hash.slice(0, 10) : '-' }}</td>
                  <td>{{ statusLabel(ev.status) }}</td>
                  <td>{{ ev.cost_ms }}ms</td>
                  <td>
                    <button
                      v-if="ev.workflow_run_id"
                      class="small"
                      title="查看这次运行的操作证据链"
                      @click="openEvidence(ev.workflow_run_id)"
                    >查看</button>
                    <span v-else class="hash-cell">-</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
          <div v-else class="empty small">选择用户与时间窗后点「回放」，可看到每次工具调用是放行还是被拦截、命中哪条策略</div>
        </div>

        <!-- 按工具统计 -->
        <div v-else class="table-wrap">
          <div v-if="!stats.length" class="empty small">暂无工具调用统计</div>
          <table v-else>
            <thead>
              <tr><th>工具</th><th>类型</th><th>次数</th><th>成功</th><th>失败</th><th>拦截</th><th>平均耗时(ms)</th><th>最后调用</th></tr>
            </thead>
            <tbody>
              <tr v-for="(s, i) in stats" :key="i">
                <td><b>{{ s.tool_name }}</b></td>
                <td>{{ toolTypeLabel(s.tool_type) }}</td>
                <td>{{ s.total }}</td>
                <td>{{ s.success_count }}</td>
                <td>{{ s.fail_count }}</td>
                <td>{{ s.denied_count || 0 }}</td>
                <td>{{ s.avg_cost_ms }}</td>
                <td>{{ formatTime(s.last_time) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>

    <EvidenceDrawer
      v-if="evidenceOpen"
      :pkg="evidencePkg"
      :loading="evidenceLoading"
      @close="evidenceOpen = false"
    />
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

.status-badge.warn {
  background: color-mix(in srgb, var(--warning, #b45309) 16%, transparent);
  color: var(--warning, #b45309);
}

.replay-wrap {
  display: grid;
  gap: 12px;
}

.replay-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--muted);
}

.replay-form input {
  width: 80px;
  margin-left: 4px;
}

.replay-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--bg-soft, rgba(127, 127, 127, 0.08));
  font-size: 13px;
}

.replay-summary .danger {
  color: var(--danger);
}

tr.denied {
  background: color-mix(in srgb, var(--danger) 8%, transparent);
}

.hash-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--muted);
}
</style>
