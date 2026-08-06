<script setup>
import { ref, watch, onMounted } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';
import { kbsStore } from '@/stores/kbs';

const evalKbId = ref('');
const evalTags = ref('');
const evalRerankMode = ref('heuristic');
const result = ref(null);
const loading = ref(false);

// Agent 评估（Plan-Execute）
const agentResult = ref(null);
const agentLoading = ref(false);

async function runAgentEvaluation() {
  agentLoading.value = true;
  agentResult.value = null;
  try {
    agentResult.value = await api('/api/agent-evaluations', { method: 'POST' });
  } catch (err) {
    agentResult.value = { error: err.message };
    showToast(err.message, true);
  } finally {
    agentLoading.value = false;
  }
}

function pct(v) {
  return ((v || 0) * 100).toFixed(1) + '%';
}

function parseTags(value) {
  if (!value) return undefined;
  const tags = value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
  return tags.length ? tags : undefined;
}

async function ensureKbs() {
  try {
    await kbsStore.load();
    // 自动选中第一个知识库
    if (!evalKbId.value && kbsStore.kbs.length) {
      evalKbId.value = kbsStore.kbs[0].id;
    }
  } catch (err) {
    showToast(err.message, true);
  }
}

async function runEvaluation() {
  const kbId = Number(evalKbId.value);
  if (!kbId) {
    showToast('请选择知识库', true);
    return;
  }
  loading.value = true;
  result.value = null;
  try {
    result.value = await api('/api/evaluations/retrieval', {
      method: 'POST',
      body: JSON.stringify({
        knowledgeBaseId: kbId,
        tags: parseTags(evalTags.value),
        rerankMode: evalRerankMode.value
      })
    });
  } catch (err) {
    result.value = { error: err.message };
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

watch(
  () => session.reloadKey,
  () => {
    evalKbId.value = '';
    ensureKbs();
  }
);

onMounted(ensureKbs);
</script>

<template>
  <section class="eval-stack">
    <!-- Agent 评估（Plan-Execute） -->
    <div class="panel">
      <div class="panel-header">
        <h2>Agent 评估（Plan-Execute）</h2>
        <button class="primary" :disabled="agentLoading" @click="runAgentEvaluation">
          {{ agentLoading ? '评估中（需调用真实模型，较慢）…' : '运行 Agent 评估' }}
        </button>
      </div>
      <div v-if="agentResult && !agentResult.error" class="agent-summary">
        <div class="stat"><b>{{ agentResult.total }}</b><span>用例总数</span></div>
        <div class="stat"><b>{{ agentResult.passed }}</b><span>通过</span></div>
        <div class="stat"><b>{{ pct(agentResult.passRate) }}</b><span>通过率</span></div>
        <div class="stat"><b>{{ agentResult.planUsedCount }}</b><span>使用计划</span></div>
        <div class="stat"><b>{{ agentResult.replanCount }}</b><span>累计重规划</span></div>
      </div>
      <div v-else-if="agentResult && agentResult.error" class="empty">{{ agentResult.error }}</div>
      <div v-else class="empty">运行 Agent 评估：真实模型跑一组多工具/多步任务，统计编排成功率与计划使用情况。</div>
      <div v-if="agentResult && agentResult.items && agentResult.items.length" class="table-wrap">
        <table>
          <thead>
            <tr><th>问题</th><th>期望工具</th><th>实际调用</th><th>工具匹配</th><th>全部成功</th><th>使用计划</th></tr>
          </thead>
          <tbody>
            <tr v-for="(item, i) in agentResult.items" :key="i">
              <td class="q">{{ item.question }}</td>
              <td>{{ (item.expectedTools || []).join(', ') || '—' }}</td>
              <td>{{ (item.calledTools || []).join(', ') || '—' }}</td>
              <td :class="item.toolMatch ? 'ok' : 'bad'">{{ item.toolMatch ? '✓' : '✗' }}</td>
              <td :class="item.toolsOk ? 'ok' : 'bad'">{{ item.toolsOk ? '✓' : '✗' }}</td>
              <td :class="item.planUsed ? 'ok' : ''">{{ item.planUsed ? '📋' : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="eval-grid scroll-grid">
    <div class="panel eval-form">
      <h2>检索评估</h2>
      <label>知识库
        <select v-model="evalKbId">
          <option v-for="kb in kbsStore.kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select>
      </label>
      <label>Rerank 模式
        <select v-model="evalRerankMode">
          <option value="heuristic">启发式（快速）</option>
          <option value="model">真实模型（较慢）</option>
        </select>
      </label>
      <label>标签过滤（逗号分隔，可选）
        <input v-model="evalTags" placeholder="例如：mysql,索引">
      </label>
      <button class="primary" :disabled="loading" @click="runEvaluation">
        {{ loading ? '评估中…' : '运行 50 条评估' }}
      </button>
    </div>
    <div class="panel eval-result" :class="{ empty: !result }">
      <div v-if="!result" class="empty">评估结果会显示在这里</div>
      <template v-else-if="result.error">
        <div class="empty">{{ result.error }}</div>
      </template>
      <template v-else>
        <div class="panel-header">
          <h2>评估结果</h2>
          <span class="status COMPLETED">命中率 {{ (result.hitRate * 100).toFixed(1) }}%</span>
        </div>
        <p>总计 {{ result.total }} 条，命中 {{ result.hits }} 条。</p>
        <div class="panel-header sub">
          <h3>按主题</h3>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>主题</th><th>条数</th><th>命中</th><th>命中率</th></tr>
            </thead>
            <tbody>
              <tr v-for="t in result.topics" :key="t.topic">
                <td>{{ t.topic }}</td>
                <td>{{ t.total }}</td>
                <td>{{ t.hits }}</td>
                <td>{{ (t.hitRate * 100).toFixed(1) }}%</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>问题</th><th>期望关键词</th><th>命中</th><th>召回</th></tr>
            </thead>
            <tbody>
              <tr v-for="(item, i) in result.items" :key="i">
                <td>{{ item.question }}</td>
                <td>{{ item.expectedKeyword }}</td>
                <td>{{ item.hit ? '是' : '否' }}</td>
                <td>{{ item.retrieved }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
    </div>
  </section>
</template>

<style scoped>
.eval-stack {
  display: grid;
  gap: 16px;
}

.agent-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 10px 0;
}

.agent-summary .stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--alt-bg);
  min-width: 88px;
}

.agent-summary .stat b {
  font-size: 18px;
  color: var(--accent);
}

.agent-summary .stat span {
  font-size: 12px;
  color: var(--muted);
}

.eval-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 360px 1fr;
  align-items: start;
}

/* 检索评估区块：内容多时整体内部滚动，不撑高页面 */
.scroll-grid {
  height: calc(100vh - 420px);
  min-height: 240px;
  overflow-y: auto;
}

@media (max-width: 900px) {
  .scroll-grid {
    height: auto;
    overflow: visible;
  }
}

.eval-form {
  display: grid;
  gap: 12px;
}

.eval-result p {
  margin: 8px 0;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.table-wrap td.q {
  max-width: 340px;
}

.table-wrap td.ok {
  color: var(--ok);
}

.table-wrap td.bad {
  color: var(--danger);
}

@media (max-width: 900px) {
  .eval-grid {
    grid-template-columns: 1fr;
  }
}
</style>
