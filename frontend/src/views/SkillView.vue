<script setup>
import { ref, onMounted } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';

// 技能列表
const skills = ref([]);
const loading = ref(false);
const scopeFilter = ref('all');
const sortFilter = ref('default');
const form = ref(null); // 编辑中的技能
const saving = ref(false);

// 技能内容折叠展开（内容过长时默认折叠，避免列表被撑高出现微滚动）
const expanded = ref(new Set());

function toggleExpand(id) {
  const next = new Set(expanded.value);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  expanded.value = next;
}

// 技能健康度（Guide-55 高优先级）：总数/启用/命中 + 热门 Top5 + 僵尸技能
const stats = ref(null);
const statsLoading = ref(false);
const showZombies = ref(false); // 僵尸技能面板是否展开

// 记忆管理入口（Guide-55：专用记忆管理）
const showMemoryPanel = ref(false);
const memoryItems = ref([]);
const memoryText = ref('');
const memoryLoading = ref(false);
const memorySaving = ref(false);

// 引用资源候选（工作流 / 知识库）
const workflows = ref([]);
const kbs = ref([]);

async function load() {
  loading.value = true;
  try {
    skills.value = await api(`/api/skills?scope=${scopeFilter.value}&sort=${sortFilter.value}`);
  } catch (err) {
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

async function loadStats() {
  statsLoading.value = true;
  try {
    stats.value = await api('/api/skills/stats');
  } catch (err) {
    stats.value = null;
  } finally {
    statsLoading.value = false;
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

// ---- 记忆管理（专用入口，Guide-55 中优先级） ----
function memorySourceLabel(source) {
  return source === 'manual' ? '手动' : '自动';
}

async function openMemoryPanel() {
  showMemoryPanel.value = true;
  await loadMemory();
}

async function loadMemory() {
  memoryLoading.value = true;
  try {
    const data = await api('/api/agent/memory');
    memoryItems.value = Array.isArray(data) ? data : [];
  } catch (err) {
    memoryItems.value = [];
  } finally {
    memoryLoading.value = false;
  }
}

async function saveMemory() {
  memorySaving.value = true;
  try {
    const added = memoryText.value.split('\n')
      .map((l) => l.trim())
      .filter(Boolean)
      .map((l) => {
        const idx = l.indexOf(':');
        if (idx === -1) return null;
        return { key: l.slice(0, idx).trim(), value: l.slice(idx + 1).trim() };
      })
      .filter(Boolean);
    const items = [
      ...memoryItems.value.map((m) => ({ key: m.key, value: m.value })),
      ...added
    ];
    await api('/api/agent/memory', { method: 'PUT', body: JSON.stringify({ items }) });
    memoryText.value = '';
    await loadMemory();
    showToast('长期记忆已保存');
  } catch (err) {
    showToast(err.message, true);
  } finally {
    memorySaving.value = false;
  }
}

async function deleteMemory(item) {
  try {
    await api(`/api/agent/memory/${item.id}`, { method: 'DELETE' });
    memoryItems.value = memoryItems.value.filter((m) => m.id !== item.id);
    showToast('已删除该条记忆');
  } catch (err) {
    showToast(err.message, true);
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
  loadStats();
  loadCandidates();
});
</script>

<template>
  <section class="skill-grid">
    <!-- 健康度概览（Guide-55 高优先级：热门/僵尸一目了然） -->
    <div v-if="statsLoading" class="panel health-panel">
      <h2>技能健康度</h2>
      <div class="empty">统计加载中…</div>
    </div>
    <div v-else-if="stats" class="panel health-panel">
      <h2>技能健康度</h2>
      <div class="health-cards">
        <div class="health-card">
          <b>{{ stats.total ?? 0 }}</b>
          <span>技能总数</span>
        </div>
        <div class="health-card">
          <b>{{ stats.enabled ?? 0 }}</b>
          <span>启用中</span>
        </div>
        <div class="health-card">
          <b>{{ stats.hitTotal ?? 0 }}</b>
          <span>累计命中</span>
        </div>
      </div>
      <div class="health-cols">
        <div class="health-col">
          <div class="health-col-title">🔥 热门技能（命中 Top）</div>
          <div v-if="!stats.hot || !stats.hot.length" class="empty small">暂无命中记录，技能一旦被 Agent 使用就会出现在这里</div>
          <div v-else class="hot-list">
            <div v-for="(s, i) in stats.hot" :key="s.id" class="hot-item">
              <span class="hot-rank" :class="'r' + (i + 1)">{{ i + 1 }}</span>
              <span class="hot-name" :title="s.name">{{ s.name }}</span>
              <span class="hot-count">{{ s.hitCount }} 次</span>
            </div>
          </div>
        </div>
        <div class="health-col">
          <div class="health-col-title">🥶 僵尸技能（启用但 0 命中）</div>
          <div v-if="!stats.zombie || !stats.zombie.length" class="empty small">没有僵尸技能 🎉</div>
          <div v-else>
            <div class="zombie-note">以下 {{ stats.zombie.length }} 个技能已启用但从未被 Agent 命中，可能是触发词不匹配或描述不清晰：</div>
            <div v-for="s in stats.zombie" :key="s.id" class="zombie-item">
              <span class="zombie-name" :title="s.name">{{ s.name }}</span>
              <button class="link small" @click="openEdit(s)">检查</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel scroll-panel">
      <h2>技能（Skills）</h2>
      <p class="hint">技能是"某类任务该怎么做"的规范。团队技能全员生效，个人仅自己；也可在"流程"页把跑通的工作流另存为技能。</p>
      <div class="toolbar">
        <select v-model="scopeFilter" @change="load">
          <option value="all">全部</option>
          <option value="team">团队技能</option>
          <option value="personal">个人技能</option>
        </select>
        <select v-model="sortFilter" @change="load">
          <option value="default">最近创建</option>
          <option value="hot">按命中热度</option>
          <option value="zombie">命中最少在前</option>
        </select>
        <button class="primary" @click="openCreate">新建技能</button>
        <button class="small" @click="openMemoryPanel">🧠 记忆管理</button>
      </div>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!skills.length" class="empty">
        <span v-if="sortFilter === 'hot'">还没有命中记录。技能被 Agent 使用后会自动累积命中数。</span>
        <span v-else>还没有技能。可以把跑通的工作流另存为技能，或手动创建一个。</span>
      </div>
      <div v-else class="skill-list">
        <div v-for="s in skills" :key="s.id" class="skill-card" :class="{ disabled: !s.enabled }">
          <div class="head">
            <b>{{ s.name }}</b>
            <span class="scope" :class="s.scope">{{ s.scope === 'team' ? '团队' : '个人' }}</span>
            <span class="status" :class="s.enabled ? 'on' : 'off'">{{ s.enabled ? '启用' : '停用' }}</span>
            <span class="hit" :class="{ zero: s.hitCount === 0 }" :title="s.hitCount > 0 ? '被 Agent 命中次数' : '从未被命中，可能触发词不匹配'">
              {{ s.hitCount > 0 ? '🔥 ' + s.hitCount : '· 0 命中' }}
            </span>
          </div>
          <div v-if="s.description" class="desc">{{ s.description }}</div>
          <div class="apply">触发：<span>{{ s.applyTo || '—' }}</span></div>
          <div v-if="parseReferences(s.references).length" class="refs">
            引用资源：
            <span v-for="r in parseReferences(s.references)" :key="r.type + r.id" class="ref-tag" :class="r.type">
              {{ r.type === 'workflow' ? '工作流' : '知识库' }}：{{ r.name || ('ID ' + r.id) }}
            </span>
          </div>
          <pre class="content" :class="{ folded: !expanded.has(s.id) }">{{ s.content }}</pre>
          <button v-if="s.content && s.content.length > 120" class="link small expand-btn" @click="toggleExpand(s.id)">
            {{ expanded.has(s.id) ? '收起内容' : '展开内容' }}
          </button>
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

    <!-- 记忆管理（专用入口，Guide-55 中优先级） -->
    <div v-if="showMemoryPanel" class="panel memory-panel">
      <h2>🧠 长期记忆 <button class="link small close-btn" @click="showMemoryPanel = false">关闭</button></h2>
      <p class="hint">Agent 在对话中会自动提取你的偏好（来源=自动），你也可以手动补充/删除。
        记忆跨会话保留，会影响 Agent 的回答风格与取值习惯。</p>
      <div v-if="memoryLoading" class="empty">加载中…</div>
      <div v-else-if="!memoryItems.length" class="empty">暂无记忆。与 Agent 对话时它会自动记住你的偏好，也可以在这里手动添加。</div>
      <div v-else class="memory-list">
        <div v-for="m in memoryItems" :key="m.id" class="memory-item">
          <div class="memory-item-main">
            <span class="memory-item-key">{{ m.key }}</span>:
            <span class="memory-item-value">{{ m.value }}</span>
          </div>
          <div class="memory-item-meta">
            <span class="memory-source" :class="m.source === 'manual' ? 'manual' : 'auto'">{{ memorySourceLabel(m.source) }}</span>
            <span class="memory-time">{{ m.updatedTime || m.createdTime || '' }}</span>
            <button class="link danger small" @click="deleteMemory(m)">删除</button>
          </div>
        </div>
      </div>
      <textarea
        v-model="memoryText"
        rows="2"
        placeholder="新增偏好，每行一条，格式：偏好: 内容&#10;例如：&#10;语言: 中文"
      ></textarea>
      <div class="memory-actions">
        <span class="memory-hint">手动添加的记忆来源标记为「手动」</span>
        <button class="secondary small" :disabled="memorySaving" @click="saveMemory">{{ memorySaving ? '保存中…' : '保存记忆' }}</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.skill-grid {
  /* 左右分栏：健康度窄栏 + 技能列表，共享固定视口高度，外层与内容区都不再出现多余滚动条 */
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  align-items: start;
  height: calc(100vh - 96px);
}

/* 技能健康度：固定窄栏，内容多时面板内滚动 */
.skill-grid .health-panel {
  height: 100%;
  overflow-y: auto;
  align-content: start;
}

/* 技能列表：占满剩余空间，内容多时面板内滚动，不撑高页面 */
.skill-grid .scroll-panel {
  height: 100%;
  overflow-y: auto;
  align-content: start;
}

@media (max-width: 900px) {
  .skill-grid {
    grid-template-columns: 1fr;
    height: auto;
  }
  .skill-grid .scroll-panel {
    height: auto;
    max-height: 420px;
    overflow-y: auto;
  }
  .skill-grid .health-panel {
    height: auto;
    max-height: 280px;
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
  flex-wrap: wrap;
}

/* 修复全局 select width:100% 在 flex 工具栏里挤压按钮（文字换行撑高） */
.toolbar select {
  width: auto;
  flex: none;
}

.toolbar button {
  flex: none;
  white-space: nowrap;
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

/* 内容折叠：默认限高，避免列表被长内容撑高 */
.content.folded {
  max-height: 48px;
  overflow: hidden;
}

.expand-btn {
  justify-self: start;
  font-size: 11px;
  padding: 2px 8px;
  line-height: 1.4;
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

/* ---- 技能健康度概览（Guide-55） ---- */
.health-panel {
  display: grid;
  gap: 12px;
}

.health-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.health-card {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 8px 10px;
  display: grid;
  gap: 2px;
}

.health-card b {
  font-size: 20px;
}

.health-card span {
  font-size: 11px;
  color: var(--muted);
}

.health-cols {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

@media (max-width: 900px) {
  .health-cols { grid-template-columns: 1fr; }
}

.health-col {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 12px;
  display: grid;
  gap: 8px;
}

.health-col-title {
  font-size: 13px;
  font-weight: 600;
}

.hot-list { display: grid; gap: 6px; }
.hot-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.hot-rank {
  width: 18px; height: 18px;
  border-radius: 50%;
  background: #eee;
  color: #666;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.hot-rank.r1 { background: #ffd54f; color: #5d4037; }
.hot-rank.r2 { background: #e0e0e0; color: #424242; }
.hot-rank.r3 { background: #d7a86e; color: #fff; }
.hot-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hot-count { font-size: 12px; color: var(--muted); flex: none; }

.zombie-note {
  font-size: 12px;
  color: #b26a00;
  background: #fff8e1;
  border-radius: 4px;
  padding: 6px 8px;
}
.zombie-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 3px 0;
}
.zombie-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 命中徽标 */
.hit {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 10px;
  background: #fff3e0;
  color: #e65100;
  margin-left: auto;
  flex: none;
}
.hit.zero {
  background: #f5f5f5;
  color: #9e9e9e;
}

.empty.small { font-size: 12px; padding: 4px 0; }

/* ---- 记忆管理面板 ---- */
.memory-panel {
  display: grid;
  gap: 10px;
}

.memory-panel h2 { display: flex; align-items: center; gap: 8px; }
.memory-panel .close-btn { margin-left: auto; }

.memory-list { display: grid; gap: 8px; }

.memory-item {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 8px 10px;
  display: grid;
  gap: 4px;
}

.memory-item-main { font-size: 13px; word-break: break-word; }
.memory-item-key { font-weight: 600; }
.memory-item-value { color: var(--muted); }

.memory-item-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 11px;
  color: var(--muted);
}

.memory-source {
  padding: 0 6px;
  border-radius: 8px;
  font-size: 11px;
}
.memory-source.manual { background: #e8f5e9; color: #2e7d32; }
.memory-source.auto { background: #e3f2fd; color: #1565c0; }

.memory-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.memory-hint { font-size: 12px; color: var(--muted); flex: 1; }
</style>
