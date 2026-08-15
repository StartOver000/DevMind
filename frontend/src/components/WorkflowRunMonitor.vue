<script setup>
/**
 * 工作流运行监视器（P2-1 前端可视化）：
 * - POST /run/stream 用 fetch + ReadableStream 解析 SSE（event: step / event: done），实时展示逐步状态
 * - done.status = WAITING_APPROVAL 时拉取审批请求并展示 human-in-the-loop 审批面板
 * - 审批通过/拒绝后轮询 run 详情直到终态（SUCCESS/FAILED/REJECTED），展示最终步骤明细
 */
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { api, getToken, getCurrentUserId } from '@/api/client';
import { showToast } from '@/stores/toast';

const props = defineProps({
  workflow: { type: Object, required: true }
});
const emit = defineEmits(['close']);

// connecting | running | waiting_approval | finished | error
const phase = ref('connecting');
const steps = ref([]);          // SSE step 事件（实时）
const run = ref(null);          // SSE done 事件（WorkflowRun）
const approvals = ref([]);      // 审批请求（WorkflowApproval[]）
const comment = ref('');
const decidingId = ref(null);
const finalDetail = ref(null);  // 终态 run 详情 { run, steps }
const error = ref('');

let controller = null;
let pollTimer = null;

const PHASE_LABEL = {
  connecting: '连接中…',
  running: '执行中…',
  waiting_approval: '等待审批',
  finished: '已结束',
  error: '出错了'
};

function parseSseEvents(raw) {
  const events = [];
  let event = 'message';
  let data = '';
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data += line.slice(5).trim();
  }
  if (data) events.push({ event, data });
  return events;
}

function handleEvent(event, data) {
  if (event === 'step') {
    const exists = steps.value.some(s =>
      s.stepIndex === data.stepIndex && s.status === data.status && s.toolName === data.toolName);
    if (!exists) steps.value.push({
      runId: data.runId,
      stepIndex: data.stepIndex,
      toolName: data.toolName,
      status: data.status,
      error: data.error
    });
    phase.value = 'running';
  } else if (event === 'done') {
    run.value = data;
    if (data.status === 'WAITING_APPROVAL') {
      phase.value = 'waiting_approval';
      loadApprovals(data.id);
    } else {
      phase.value = 'finished';
      pollFinal(data.id);
    }
  }
}

async function fetchSse() {
  controller = new AbortController();
  const headers = { 'X-User-Id': String(getCurrentUserId()) };
  const token = getToken();
  if (token) headers['Authorization'] = 'Bearer ' + token;
  try {
    const res = await fetch(`/api/workflows/${props.workflow.id}/run/stream`, {
      method: 'POST',
      headers,
      signal: controller.signal
    });
    if (!res.ok || !res.body) throw new Error('实时连接失败（HTTP ' + res.status + '）');
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        for (const { event, data } of parseSseEvents(raw)) {
          try {
            handleEvent(event, JSON.parse(data));
          } catch (e) { /* 忽略非 JSON 数据 */ }
        }
      }
    }
    if (phase.value === 'connecting' || phase.value === 'running') {
      phase.value = 'finished';
    }
  } catch (err) {
    if (err.name !== 'AbortError') {
      phase.value = 'error';
      error.value = err.message || String(err);
    }
  }
}

async function loadApprovals(runId) {
  try {
    approvals.value = await api(`/api/workflows/${props.workflow.id}/runs/${runId}/approvals`);
  } catch (err) {
    showToast(err.message, true);
  }
}

async function decide(approval, approved) {
  decidingId.value = approval.id;
  try {
    await api(
      `/api/workflows/${props.workflow.id}/runs/${approval.runId}/approvals/${approval.id}/${approved ? 'approve' : 'reject'}`,
      { method: 'POST', body: JSON.stringify({ comment: comment.value || undefined }) }
    );
    comment.value = '';
    showToast(approved ? '已批准，继续执行' : '已拒绝');
    phase.value = 'running';
    pollFinal(approval.runId);
  } catch (err) {
    showToast(err.message, true);
  } finally {
    decidingId.value = null;
  }
}

/** 轮询 run 详情直到终态 */
function pollFinal(runId) {
  clearTimeout(pollTimer);
  const tick = async () => {
    try {
      const detail = await api(`/api/workflows/runs/${runId}`);
      if (detail && detail.run && ['SUCCESS', 'FAILED', 'REJECTED'].includes(detail.run.status)) {
        finalDetail.value = detail;
        phase.value = 'finished';
        return;
      }
      pollTimer = setTimeout(tick, 1200);
    } catch (e) {
      pollTimer = setTimeout(tick, 1500);
    }
  };
  tick();
}

onMounted(fetchSse);

onBeforeUnmount(() => {
  clearTimeout(pollTimer);
  if (controller) controller.abort();
});
</script>

<template>
  <div class="run-monitor" :class="phase">
    <div class="rm-head">
      <div>
        <b>运行「{{ workflow.name }}」</b>
        <span class="phase" :class="phase">{{ PHASE_LABEL[phase] }}</span>
      </div>
      <button class="small" @click="emit('close')">关闭</button>
    </div>

    <!-- 实时步骤（SSE） -->
    <div v-if="steps.length" class="rm-steps">
      <div v-for="(s, i) in steps" :key="i" class="step-row" :class="(s.status || '').toLowerCase()">
        <span class="no">{{ i + 1 }}</span>
        <b>{{ s.toolName }}</b>
        <span class="status" :class="s.status">{{ s.status }}</span>
        <span v-if="s.error" class="err">错误：{{ s.error }}</span>
      </div>
    </div>
    <div v-else class="rm-empty">{{ phase === 'connecting' ? '正在连接执行流…' : '尚无步骤' }}</div>

    <!-- 审批面板（human-in-the-loop） -->
    <div v-if="phase === 'waiting_approval'" class="approval-panel">
      <h4>⏸ 需要审批</h4>
      <div v-if="!approvals.length" class="rm-empty">加载审批请求中…</div>
      <div v-for="a in approvals.filter(x => x.status === 'PENDING')" :key="a.id" class="approval-card">
        <div class="a-title">{{ a.title || '未命名审批' }}</div>
        <div class="a-meta">
          <span v-if="a.assignee">审批人：{{ a.assignee }}</span>
          <span>步骤 #{{ a.stepIndex + 1 }}</span>
        </div>
        <textarea v-model="comment" rows="2" placeholder="审批意见（可选）"></textarea>
        <div class="a-actions">
          <button class="primary" :disabled="decidingId === a.id" @click="decide(a, true)">
            {{ decidingId === a.id ? '处理中…' : '✅ 批准' }}
          </button>
          <button class="danger" :disabled="decidingId === a.id" @click="decide(a, false)">❌ 拒绝</button>
        </div>
      </div>
      <div v-if="approvals.length && !approvals.some(x => x.status === 'PENDING')" class="rm-empty">
        审批已处理，等待恢复执行…
      </div>
    </div>

    <!-- 运行结果（done / 终态详情） -->
    <div v-if="phase === 'finished'" class="rm-result">
      <template v-if="finalDetail">
        <h4>最终状态：{{ finalDetail.run.status }}</h4>
        <p v-if="finalDetail.run.error">错误：{{ finalDetail.run.error }}</p>
        <div v-for="(s, i) in finalDetail.steps" :key="i" class="step-row" :class="(s.status || '').toLowerCase()">
          <div class="head">
            <span class="no">{{ i + 1 }}</span>
            <b>{{ s.toolName }}</b>
            <span class="status" :class="s.status">{{ s.status }}</span>
            <span class="cost">{{ s.costMs }}ms</span>
          </div>
          <div v-if="s.error" class="err">错误：{{ s.error }}</div>
          <pre v-if="s.outputJson" class="out">{{ s.outputJson }}</pre>
        </div>
      </template>
      <template v-else-if="run">
        <h4>运行结果：{{ run.status }}</h4>
        <p v-if="run.error">错误：{{ run.error }}</p>
      </template>
    </div>

    <div v-if="phase === 'error'" class="rm-error">
      <p>⚠️ {{ error }}</p>
      <button class="small" @click="emit('close')">关闭</button>
    </div>
  </div>
</template>

<style scoped>
.run-monitor {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  display: grid;
  gap: 10px;
  margin-top: 12px;
  background: var(--bg-card, #fff);
}
.rm-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.phase {
  margin-left: 8px;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
  background: #e3f2fd;
  color: #1565c0;
}
.phase.waiting_approval { background: #fff3e0; color: #e65100; }
.phase.finished { background: #e8f5e9; color: #2e7d32; }
.phase.error { background: #ffebee; color: #c62828; }
.rm-steps { display: grid; gap: 6px; }
.rm-empty { color: var(--muted, #888); font-size: 13px; padding: 6px 0; }
.step-row {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 10px;
  display: grid;
  gap: 4px;
  font-size: 13px;
}
.step-row .head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.step-row.success { border-left: 4px solid #2e7d32; }
.step-row.failed, .step-row.error { border-left: 4px solid #c62828; }
.step-row.pending, .step-row.running { border-left: 4px solid #ef6c00; }
.no {
  display: inline-block;
  width: 20px; height: 20px;
  border-radius: 50%;
  background: var(--line, #ddd);
  text-align: center;
  line-height: 20px;
  font-size: 12px;
}
.status { font-size: 11px; padding: 1px 6px; border-radius: 8px; background: #eceff1; }
.status.success { background: #e8f5e9; color: #2e7d32; }
.status.failed { background: #ffebee; color: #c62828; }
.status.pending, .status.running { background: #fff3e0; color: #ef6c00; }
.err { color: #c62828; font-size: 12px; white-space: pre-wrap; }
.out { font-size: 11px; background: #f5f5f5; padding: 6px; border-radius: 6px; white-space: pre-wrap; word-break: break-all; }
.cost { color: var(--muted, #888); font-size: 11px; }
.approval-panel {
  border: 1px dashed #ef6c00;
  border-radius: 8px;
  padding: 10px;
  display: grid;
  gap: 8px;
}
.approval-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px;
  display: grid;
  gap: 8px;
}
.a-title { font-weight: bold; }
.a-meta { color: var(--muted, #888); font-size: 12px; display: flex; gap: 12px; }
.a-actions { display: flex; gap: 8px; }
.rm-result { display: grid; gap: 8px; }
.rm-error { color: #c62828; display: grid; gap: 8px; }
</style>
