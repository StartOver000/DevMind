<script setup>
import { ref, onMounted } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';

// 技能列表
const skills = ref([]);
const loading = ref(false);
const scopeFilter = ref('all');
const form = ref(null); // 编辑中的技能
const saving = ref(false);

async function load() {
  loading.value = true;
  try {
    skills.value = await api(`/api/skills?scope=${scopeFilter.value}`);
  } catch (err) {
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  form.value = {
    id: null,
    scope: 'team',
    name: '',
    description: '',
    applyTo: '',
    content: '',
    enabled: true
  };
}

function openEdit(s) {
  form.value = { ...s };
}

async function save() {
  if (!form.value.name.trim()) { showToast('请输入技能名称', true); return; }
  if (!form.value.content.trim()) { showToast('请输入技能内容', true); return; }
  saving.value = true;
  try {
    const body = JSON.stringify({
      scope: form.value.scope,
      name: form.value.name.trim(),
      description: form.value.description || '',
      applyTo: form.value.applyTo || '',
      content: form.value.content,
      enabled: form.value.enabled
    });
    if (form.value.id) {
      await api(`/api/skills/${form.value.id}`, { method: 'PUT', body });
      showToast('技能已更新');
    } else {
      await api('/api/skills', { method: 'POST', body });
      showToast('技能已创建');
    }
    form.value = null;
    await load();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    saving.value = false;
  }
}

async function toggleSkill(s) {
  try {
    await api(`/api/skills/${s.id}/toggle?enabled=${!s.enabled}`, { method: 'POST' });
    s.enabled = !s.enabled;
    showToast(s.enabled ? '技能已启用' : '技能已停用');
  } catch (err) {
    showToast(err.message, true);
  }
}

async function removeSkill(s) {
  if (!confirm(`确认删除技能「${s.name}」？`)) return;
  try {
    await api(`/api/skills/${s.id}`, { method: 'DELETE' });
    showToast('技能已删除');
    await load();
  } catch (err) {
    showToast(err.message, true);
  }
}

onMounted(load);
</script>

<template>
  <section class="skill-grid">
    <div class="panel">
      <h2>技能（Skills）</h2>
      <p class="hint">技能是"某类任务该怎么做"的规范。Agent 遇到匹配场景时会自动遵循。
        团队技能全员生效；个人技能仅自己生效。可在"流程"页把跑通的工作流另存为技能。</p>
      <div class="toolbar">
        <select v-model="scopeFilter" @change="load">
          <option value="all">全部</option>
          <option value="team">团队技能</option>
          <option value="personal">个人技能</option>
        </select>
        <button class="primary" @click="openCreate">新建技能</button>
      </div>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!skills.length" class="empty">还没有技能。可以把跑通的工作流另存为技能，或手动创建一个。</div>
      <div v-else class="skill-list">
        <div v-for="s in skills" :key="s.id" class="skill-card" :class="{ disabled: !s.enabled }">
          <div class="head">
            <b>{{ s.name }}</b>
            <span class="scope" :class="s.scope">{{ s.scope === 'team' ? '团队' : '个人' }}</span>
            <span class="status" :class="s.enabled ? 'on' : 'off'">{{ s.enabled ? '启用' : '停用' }}</span>
          </div>
          <div v-if="s.description" class="desc">{{ s.description }}</div>
          <div class="apply">触发：<span>{{ s.applyTo || '—' }}</span></div>
          <pre class="content">{{ s.content }}</pre>
          <div class="actions">
            <button class="small" @click="openEdit(s)">编辑</button>
            <button class="small" @click="toggleSkill(s)">{{ s.enabled ? '停用' : '启用' }}</button>
            <button class="small danger" @click="removeSkill(s)">删除</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="form" class="panel editor">
      <h2>{{ form.id ? '编辑技能' : '新建技能' }}</h2>
      <label>名称
        <input v-model="form.name" placeholder="如：月度经营分析报告规范">
      </label>
      <label>适用范围
        <select v-model="form.scope">
          <option value="team">团队（全员生效）</option>
          <option value="personal">个人（仅自己）</option>
        </select>
      </label>
      <label>描述（可选）
        <input v-model="form.description" placeholder="技能是干什么的">
      </label>
      <label>触发关键词（| 分隔，命中即注入）
        <input v-model="form.applyTo" placeholder="如：月报|经营分析|月度报告">
      </label>
      <label>技能内容（Agent 遵循的规范）
        <textarea v-model="form.content" rows="6" placeholder="生成月度经营分析报告时，必须遵守：1. 结构…；2. 必须引用数据来源…"></textarea>
      </label>
      <div class="actions">
        <button class="primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        <button @click="form = null">取消</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.skill-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 1fr;
  align-items: start;
}

.skill-grid .editor {
  display: grid;
  gap: 10px;
}

.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
}

.skill-list {
  display: grid;
  gap: 10px;
}

.skill-card {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 12px;
  display: grid;
  gap: 6px;
}

.skill-card.disabled {
  opacity: 0.6;
}

.head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.scope {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 10px;
}

.scope.team { background: #e3f2fd; color: #1565c0; }
.scope.personal { background: #e8f5e9; color: #2e7d32; }

.status {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 10px;
}

.status.on { background: #e8f5e9; color: #2e7d32; }
.status.off { background: #fbe9e7; color: #c62828; }

.desc { color: var(--muted); font-size: 13px; }

.apply { font-size: 12px; color: var(--muted); }

.apply span { color: inherit; }

.content {
  background: var(--alt-bg);
  border-radius: 4px;
  padding: 8px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}

.actions {
  display: flex;
  gap: 8px;
}

.hint {
  font-size: 12px;
  color: var(--muted, #888);
  margin: 0;
}
</style>
