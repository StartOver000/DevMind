<script setup>
import { ref, watch, onMounted } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';

// 产品数据（产品审视2 盲区 D 落地）：激活漏斗 + 活跃概览，管理员可见
// 数据源 /api/admin/product-metrics（聚合 audit_log/tool_call_log 等现有表，零新增表）
const metrics = ref(null);
const error = ref('');
const loading = ref(false);

// 漏斗阶段：注册 → 建库 → 传文档 → 提问 → 生成工作流 → 用 Agent
const funnelSteps = [
  { key: 'totalUsers', label: '注册' },
  { key: 'usersCreatedKb', label: '建知识库' },
  { key: 'usersUploadedDoc', label: '上传文档' },
  { key: 'usersAsked', label: '提问' },
  { key: 'usersGeneratedWorkflow', label: '生成工作流' },
  { key: 'usersUsedAgent', label: '用 Agent' }
];

async function loadMetrics() {
  loading.value = true;
  error.value = '';
  try {
    metrics.value = await api('/api/admin/product-metrics');
  } catch (err) {
    error.value = err.message;
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

// 阶段 → 用户数（有值时用，否则沿用上一阶段）
function stageUsers(step, idx) {
  const v = metrics.value?.[step.key];
  if (typeof v === 'number') return v;
  // 某些阶段可能缺失：回退到前一阶段的值
  const prev = funnelSteps[idx - 1];
  return prev ? metrics.value?.[prev.key] ?? 0 : 0;
}

// 转化率：当前阶段 / 上一阶段（×100%）
function conversion(idx) {
  const cur = stageUsers(funnelSteps[idx], idx);
  const prev = idx === 0 ? metrics.value?.totalUsers ?? 1 : stageUsers(funnelSteps[idx - 1], idx - 1);
  if (!prev) return 0;
  return Math.round((cur / prev) * 1000) / 10;
}

watch(() => session.reloadKey, loadMetrics);
onMounted(loadMetrics);
</script>

<template>
  <div class="metrics-stack">
    <div class="panel">
      <div class="panel-header">
        <h2>产品数据</h2>
        <span class="metrics-hint">管理员可见 · 激活漏斗看用户卡在哪一步（数据来自审计/工具调用日志）</span>
      </div>
      <div v-if="loading" class="empty small">加载中…</div>
      <div v-else-if="error" class="empty small">{{ error }}（仅管理员可查看）</div>
      <template v-else-if="metrics">
        <!-- 活跃概览 -->
        <div class="metric-cards">
          <div class="metric-card"><b>{{ metrics.totalUsers }}</b><span>注册用户</span></div>
          <div class="metric-card"><b>{{ metrics.activeUsers7d }}</b><span>7 天活跃</span></div>
          <div class="metric-card"><b>{{ metrics.asks7d }}</b><span>7 天提问</span></div>
          <div class="metric-card"><b>{{ metrics.workflowRuns7d }}</b><span>7 天工作流运行</span></div>
        </div>

        <!-- 激活漏斗 -->
        <h3 class="sec-title">激活漏斗</h3>
        <div class="funnel">
          <div
            v-for="(step, idx) in funnelSteps"
            :key="step.key"
            class="funnel-row"
            :style="{ width: Math.max(18, 100 - idx * 13) + '%' }"
          >
            <div class="funnel-head">
              <span class="funnel-step">{{ idx + 1 }}. {{ step.label }}</span>
              <span class="funnel-num">{{ stageUsers(step, idx) }} 人</span>
              <span class="funnel-conv">{{ idx === 0 ? '100%' : conversion(idx) + '%' }}</span>
            </div>
            <div class="funnel-bar" :style="{ opacity: Math.max(0.25, 1 - idx * 0.13) }"></div>
          </div>
        </div>
        <p class="funnel-tip">转化率 = 本阶段人数 ÷ 上一阶段人数。哪一步骤掉得最多，就是产品最该修的地方。</p>

        <!-- 每日活跃 -->
        <h3 class="sec-title">近 7 天每日活跃</h3>
        <div v-if="!metrics.dailyActive || !metrics.dailyActive.length" class="empty small">暂无活跃数据</div>
        <div v-else class="daily-bars">
          <div v-for="d in metrics.dailyActive" :key="d.day" class="daily-col">
            <div class="daily-bar" :style="{ height: Math.max(6, d.active_users * 24) + 'px' }"></div>
            <span class="daily-label">{{ d.day }}</span>
            <span class="daily-num">{{ d.active_users }}</span>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.metrics-stack {
  display: grid;
  gap: 16px;
}

.metrics-hint {
  color: var(--muted);
  font-size: 12px;
}

.metric-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 8px;
}

.metric-card {
  display: grid;
  gap: 2px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel-2, transparent);
}

.metric-card b {
  font-size: 24px;
  color: var(--accent);
}

.metric-card span {
  color: var(--muted);
  font-size: 12px;
}

.sec-title {
  margin: 18px 0 10px;
  font-size: 15px;
}

.funnel {
  display: grid;
  gap: 8px;
}

.funnel-row {
  min-width: 240px;
}

.funnel-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 3px;
}

.funnel-step {
  font-weight: 600;
  font-size: 13px;
}

.funnel-num {
  color: var(--accent);
  font-weight: 700;
}

.funnel-conv {
  color: var(--muted);
  font-size: 12px;
}

.funnel-bar {
  height: 22px;
  border-radius: 4px;
  background: var(--accent);
}

.funnel-tip {
  color: var(--muted);
  font-size: 12px;
  margin-top: 8px;
}

.daily-bars {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  height: 150px;
  padding: 10px 4px 0;
}

.daily-col {
  display: grid;
  justify-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.daily-bar {
  width: 22px;
  border-radius: 4px 4px 0 0;
  background: var(--accent);
}

.daily-label {
  font-size: 11px;
  color: var(--muted);
}

.daily-num {
  font-size: 11px;
  font-weight: 600;
}
</style>
