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

// 引用资源候选（工作流 / 知识库）
const workflows = ref([]);
const kbs = ref([]);

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

async function loadCandidates() {
  try {
    const [wf, kb] = await Promise.all([
      api('/api/workflows'),
      api('/api/knowledge-bases')
    ]);
    workflows.value = Array.isArray(wf) ? wf : [];
    const kbList = (kb && kb.items) ? kb.items : (Array.isArray(kb) ? kb : []);
    kbs.value = kbList.filter((k) => k.status !== 'DELETED');
  } catch (err) {
    // 引用资源候选加载失败不阻塞技能管理
    workflows.value = [];
    kbs.value = [];
  }
}

// 解析技能 references（JSON 文本）为 {type,id,name} 数组
function parseReferences(raw) {
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch (e) {
    return [];
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
    references: [],
    enabled: true
  };
}

function openEdit(s) {
  form.value = {
    ...s,
    references: parseReferences(s.references)
  };
}

function toggleRef(type, id, name, checked) {
  if (!form.value) return;
  const arr = form.value.references;
  const idx = arr.findIndex((r) => r.type === type && r.id === id);
  if (checked) {
    if (idx === -1) arr.push({ type, id, name });
  } else if (idx !== -1) {
    arr.splice(idx, 1);
  }
}

function refSelected(type, id) {
  return form.value && form.value.references.some((r) => r.type === type && r.id === id);
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
      references: JSON.stringify(form.value.references || []),
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

onMounted(() => {
  load();
  loadCandidates();
});
</script>

<template>
  <section class="skill-grid">
    <div class="panel scroll-panel">
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
          <div v-if="parseReferences(s.references).length" class="refs">
            引用资源：
            <span v-for="r in parseReferences(s.references)" :key="r.type + r.id" class="ref-tag" :class="r.type">
              {{ r.type === 'workflow' ? '工作流' : '知识库' }}：{{ r.name || ('ID ' + r.id) }}
            </span>
          </div>
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
      <div class="ref-editor">
        <label>引用资源（可选）<span class="ref-hint">命中技能时，Agent 可联动执行引用的工作流 / 检索引用的知识库</span></label>
        <div v-if="workflows.length" class="ref-group">
          <span class="ref-group-title">工作流（命中后可执行）</span>
          <label v-for="w in workflows" :key="'wf' + w.id" class="ref-option">
            <input type="checkbox" :checked="refSelected('workflow', w.id)" @change="toggleRef('workflow', w.id, w.name, $event.target.checked)">
            {{ w.name }}
          </label>
        </div>
        <div v-if="kbs.length" class="ref-group">
          <span class="ref-group-title">知识库（命中后可检索）</span>
          <label v-for="k in kbs" :key="'kb' + k.id" class="ref-option">
            <input type="checkbox" :checked="refSelected('kb', k.id)" @change="toggleRef('kb', k.id, k.name, $event.target.checked)">
            {{ k.name }}
          </label>
        </div>
        <div v-if="!workflows.length && !kbs.length" class="ref-empty">暂无可引用资源（先创建工作流或知识库）</div>
      </div>
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

/* 技能列表：内容多时面板内滚动，不撑高页面 */
.skill-grid .scroll-panel {
  height: calc(100vh - 110px);
  overflow-y: auto;
  align-content: start;
}

@media (max-width: 900px) {
  .skill-grid .scroll-panel {
    height: auto;
    max-height: 420px;
    overflow-y: auto;
  }
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

.refs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
  align-items: center;
}

.ref-tag {
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
}

.ref-tag.workflow { background: #ede7f6; color: #4527a0; }
.ref-tag.kb { background: #e0f2f1; color: #00695c; }

.ref-editor {
  display: grid;
  gap: 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px;
}

.ref-hint {
  color: var(--muted);
  font-size: 11px;
  margin-left: 6px;
}

.ref-group {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  align-items: center;
}

.ref-group-title {
  font-size: 12px;
  color: var(--muted);
  width: 100%;
}

.ref-option {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}

.ref-empty {
  color: var(--muted);
  font-size: 12px;
}

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
