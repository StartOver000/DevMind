<script setup>
import { computed } from 'vue';

const props = defineProps({
  pkg: { type: Object, default: null },
  loading: { type: Boolean, default: false }
});
const emit = defineEmits(['close']);

const ACTION_LABEL = { APPROVE: '审批通过', REJECT: '审批拒绝', EXECUTE: '执行' };

const actions = computed(() => {
  const p = props.pkg;
  if (!p) return '';
  const parts = [];
  if (p.approved) parts.push('审批通过');
  if (p.rejected) parts.push('审批拒绝');
  if (p.executed) parts.push('已执行');
  return parts.join(' · ');
});

function short(hash) {
  return hash ? hash.slice(0, 8) : '—';
}
</script>

<template>
  <div class="evidence-drawer" @click.self="emit('close')">
    <aside class="evidence-panel">
      <div class="evidence-head">
        <h3>操作证据<span v-if="pkg"> · run #{{ pkg.runId }}</span></h3>
        <button class="ghost" @click="emit('close')">关闭</button>
      </div>

      <div v-if="loading" class="evidence-loading">加载中…</div>
      <template v-else-if="pkg">
        <div class="evidence-status">
          <span v-if="pkg.valid" class="evidence-valid">✓ 链完整</span>
          <span v-else-if="pkg.recordCount === 0" class="evidence-empty">{{ pkg.reason || '无证据：该运行没有审批或执行记录' }}</span>
          <span v-else class="evidence-invalid">✗ 链断开：第 {{ pkg.brokenIndex }} 条 — {{ pkg.reason }}</span>
        </div>

        <div class="evidence-summary">
          <div><span class="k">决定</span><span>{{ actions || '—' }}</span></div>
          <div><span class="k">决定人</span><span>{{ pkg.decidedBy || '—' }}</span></div>
          <div><span class="k">记录</span><span>{{ pkg.recordCount }} 条</span></div>
          <div><span class="k">末条哈希</span><span class="hash">{{ short(pkg.lastRecordHash) }}</span></div>
        </div>

        <table class="evidence-table">
          <thead>
            <tr><th>#</th><th>动作</th><th>节点</th><th>决定人</th><th>前序哈希</th><th>状态摘要</th><th>本条哈希</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="(r, i) in pkg.records"
              :key="i"
              :class="{ broken: !pkg.valid && i + 1 === pkg.brokenIndex }"
            >
              <td>{{ i + 1 }}</td>
              <td>{{ ACTION_LABEL[r.action] || r.action }}</td>
              <td>{{ r.nodeTitle || '—' }}</td>
              <td>{{ r.decidedBy || '—' }}</td>
              <td class="hash">{{ short(r.prevHash) }}</td>
              <td class="hash">{{ short(r.beforeHash) }} → {{ short(r.afterHash) }}</td>
              <td class="hash">{{ short(r.recordHash) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="evidence-hint">哈希为 SHA-256 前 8 位；状态摘要 = 决定前快照摘要 → 执行后快照摘要（原文不入库）。</p>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.evidence-drawer {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 32, 0.45);
  display: flex;
  justify-content: flex-end;
  z-index: 60;
}

.evidence-panel {
  width: min(720px, 92vw);
  height: 100%;
  overflow: auto;
  background: var(--panel);
  padding: 16px;
  box-shadow: -8px 0 24px rgba(0, 0, 0, 0.18);
}

.evidence-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.evidence-head h3 {
  margin: 0;
  font-size: 16px;
}

.evidence-status {
  margin: 8px 0 12px;
}

.evidence-valid {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 13px;
  background: rgba(34, 197, 94, 0.14);
  color: #15803d;
  border: 1px solid rgba(34, 197, 94, 0.35);
}

.evidence-invalid {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 13px;
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
  border: 1px solid rgba(220, 38, 38, 0.35);
}

.evidence-empty {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 13px;
  background: rgba(148, 163, 184, 0.14);
  color: #475569;
  border: 1px solid rgba(148, 163, 184, 0.35);
}

.evidence-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 16px;
  margin-bottom: 12px;
  font-size: 13px;
}

.evidence-summary .k {
  display: inline-block;
  min-width: 64px;
  color: var(--muted, #6b7280);
}

.evidence-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.evidence-table th,
.evidence-table td {
  text-align: left;
  padding: 6px 8px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.25);
}

.evidence-table .hash {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #475569;
}

.evidence-table tr.broken {
  background: rgba(220, 38, 38, 0.08);
}

.evidence-hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--muted, #6b7280);
}

.evidence-loading {
  padding: 24px 0;
  color: var(--muted, #6b7280);
}

.ghost {
  background: transparent;
  border: 1px solid rgba(148, 163, 184, 0.4);
  border-radius: 6px;
  padding: 4px 10px;
  cursor: pointer;
}
</style>
