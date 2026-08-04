<script setup>
import { ref, watch, onMounted } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';
import { kbsStore } from '@/stores/kbs';
import { openModal } from '@/stores/modal';
import SqlHistory from '@/components/SqlHistory.vue';

const sqlDataSource = ref('mysql');
const sqlKbId = ref('');
const sqlText = ref('');
const result = ref(null);
const loading = ref(false);

async function ensureKbs() {
  try {
    await kbsStore.load();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function runSqlDiagnosis() {
  const sql = sqlText.value.trim();
  const dataSource = sqlDataSource.value.trim() || 'mysql';
  const knowledgeBaseId = sqlKbId.value ? Number(sqlKbId.value) : null;
  if (!sql) {
    showToast('请输入 SQL', true);
    return;
  }
  loading.value = true;
  result.value = null;
  try {
    result.value = await api('/api/sql-diagnosis', {
      method: 'POST',
      body: JSON.stringify({ sql, dataSource, knowledgeBaseId })
    });
  } catch (err) {
    result.value = { error: err.message };
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

function showHistory() {
  openModal('最近诊断记录', SqlHistory);
}

watch(
  () => session.reloadKey,
  () => {
    sqlKbId.value = '';
    ensureKbs();
  }
);

onMounted(ensureKbs);
</script>

<template>
  <section class="sql-grid">
    <div class="panel sql-form">
      <h2>SQL 执行计划诊断</h2>
      <label>数据源
        <input v-model="sqlDataSource" placeholder="数据源标识">
      </label>
      <label>知识库（可选）
        <select v-model="sqlKbId">
          <option value="">不使用知识库</option>
          <option v-for="kb in kbsStore.kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select>
      </label>
      <textarea
        v-model="sqlText"
        rows="6"
        maxlength="2000"
        placeholder="输入 SELECT 语句，例如：SELECT * FROM orders ORDER BY created_time LIMIT 100000, 20"
      ></textarea>
      <div class="actions">
        <button class="primary" :disabled="loading" @click="runSqlDiagnosis">
          {{ loading ? '诊断中…' : '开始诊断' }}
        </button>
        <button class="secondary" @click="showHistory">最近记录</button>
      </div>
    </div>
    <div class="panel sql-result" :class="{ empty: !result }">
      <div v-if="!result" class="empty">诊断结果会显示在这里</div>
      <template v-else-if="result.error">
        <div class="empty">{{ result.error }}</div>
      </template>
      <template v-else>
        <div class="panel-header">
          <h2>诊断结果</h2>
          <span class="risk-level" :class="result.riskLevel">{{ result.riskLevel }}</span>
        </div>
        <p>SQL：<code>{{ result.sql }}</code></p>
        <h3>风险清单</h3>
        <div v-if="result.risks.length">
          <div v-for="(risk, i) in result.risks" :key="i" class="reference">
            <div class="head">
              <span>{{ risk.rule }}</span>
              <span class="score">{{ risk.level }}</span>
            </div>
            <p>{{ risk.message }}</p>
          </div>
        </div>
        <p v-else>未发现明显风险</p>
        <h3>执行计划</h3>
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>表</th><th>type</th><th>key</th><th>rows</th><th>Extra</th></tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in result.plan" :key="i">
                <td>{{ row.table }}</td>
                <td>{{ row.type }}</td>
                <td>{{ row.key || '-' }}</td>
                <td>{{ row.rows }}</td>
                <td>{{ row.extra || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <h3>AI 建议</h3>
        <div class="answer">{{ result.advice }}</div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.sql-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 380px 1fr;
  align-items: start;
}

.sql-form {
  display: grid;
  gap: 12px;
}

.sql-result h3 {
  margin: 16px 0 4px;
  font-size: 14px;
}

.sql-result p {
  margin: 8px 0;
}

@media (max-width: 900px) {
  .sql-grid {
    grid-template-columns: 1fr;
  }
}
</style>
