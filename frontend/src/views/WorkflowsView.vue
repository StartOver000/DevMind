<script setup>
import { ref, onMounted, h } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';
import WorkflowEditor from '@/components/WorkflowEditor.vue';
import WorkflowRunMonitor from '@/components/WorkflowRunMonitor.vue';

// 递归渲染工作流草案节点（step / if / parallel）
const DraftNode = {
  props: { node: Object, index: [Number, String] },
  setup(props) {
    return () => {
      const n = props.node;
      if (n.kind === 'if') {
        const thenKids = (n.thenBranch || []).map((s, i) => h(DraftNode, { key: 't' + i, node: s, index: i }));
        const elseKids = (n.elseBranch || []).map((s, i) => h(DraftNode, { key: 'e' + i, node: s, index: i }));
        return h('div', { class: 'draft-branch' }, [
          h('div', { class: 'branch-head' }, [`🔀 如果 ${n.condition || ''}`]),
          h('div', { class: 'branch-body' }, [h('div', { class: 'branch-label' }, '满足时'), ...thenKids]),
          (n.elseBranch && n.elseBranch.length)
            ? h('div', { class: 'branch-body' }, [h('div', { class: 'branch-label' }, '否则'), ...elseKids])
            : null
        ]);
      }
      if (n.kind === 'parallel') {
        const kids = (n.parallelSteps || []).map((s, i) => h(DraftNode, { key: i, node: s, index: i }));
        return h('div', { class: 'draft-branch' }, [
          h('div', { class: 'branch-head' }, ['⚡ 并行执行']),
          h('div', { class: 'branch-body' }, kids)
        ]);
      }
      return h('div', { class: 'draft-step' }, [
        h('span', { class: 'no' }, String((props.index === undefined ? '' : Number(props.index) + 1))),
        h('div', { class: 'body' }, [
          // 业务人员视角（产品审视：业务人员视角审视-20260816.md 卡点4）：
          // 标题优先显示业务化 goal；工具名/参数/变量收进可折叠"技术细节"
          h('div', { class: 'step-title' }, [h('b', n.goal || n.tool || '')]),
          (n.tool || (n.paramsJson && n.paramsJson !== '{}') || n.outputVar)
            ? h('details', { class: 'step-detail' }, [
                h('summary', '技术细节'),
                h('div', { class: 'detail-inner' }, [
                  n.tool ? h('div', ['工具：', h('code', n.tool)]) : null,
                  (n.paramsJson && n.paramsJson !== '{}') ? h('div', { class: 'params' }, ['参数：', n.paramsJson]) : null,
                  n.outputVar ? h('div', { class: 'out' }, ['输出：', n.outputVar]) : null
                ])
              ])
            : null
        ])
      ]);
    };
  }
};

// 对话式创建
const description = ref('');
const generating = ref(false);
const draft = ref(null); // { steps, stepsJson }
const workflowName = ref('');
const triggerType = ref('manual');
const cronExpr = ref('');
const creating = ref(false);

// 缺失能力反推（能力盘点）：需求 → 命中接口 + 覆盖/缺口分析
const capAnalyzing = ref(false);
const capAnalysis = ref(null); // { description, matchedInterfaces, steps, gaps, warnings }

/** 统计草案中的并行组/分支数量（对话生成 parallel 产品化：生成后给出结构提示） */
function countStructure(nodes) {
  let parallel = 0;
  let branches = 0;
  const walk = (list) => {
    for (const n of list || []) {
      if (n.kind === 'parallel') {
        parallel++;
        walk(n.parallelSteps);
      } else if (n.kind === 'if') {
        branches++;
        walk(n.thenBranch);
        walk(n.elseBranch);
      }
    }
  };
  walk(nodes);
  return { parallel, branches };
}

function draftBadges() {
  if (!draft.value || !draft.value.steps) return [];
  const { parallel, branches } = countStructure(draft.value.steps);
  const badges = [];
  if (parallel > 0) badges.push({ text: `⚡ 并行组 ×${parallel}`, cls: 'badge-parallel' });
  if (branches > 0) badges.push({ text: `🔀 条件分支 ×${branches}`, cls: 'badge-branch' });
  return badges;
}

// 状态中文化（业务人员视角审视卡点5）：ENABLED→已启用、SUCCESS→成功等
function wfStatusLabel(s) {
  const map = {
    ENABLED: '已启用', DISABLED: '已停用',
    SUCCESS: '成功', RUNNING: '运行中', FAILED: '失败',
    WAITING: '等待中', PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝',
    COMPLETED: '已完成'
  };
  return map[s] || s;
}

// 工作流列表
const workflows = ref([]);
const loading = ref(false);
const runningId = ref(null);
const monitorWorkflow = ref(null); // 运行监视器（SSE 实时进度 + 审批）

// 可视化编辑（Guide-55 高优先级）
const editorWorkflow = ref(null);   // 正在编辑的工作流
const editorSteps = ref([]);        // 解析后的结构对象数组
const savingEditor = ref(false);

// 草稿可视化调整（对话生成 parallel 深度引导）：生成草稿后可编辑并行/分支再创建
const draftEditing = ref(false);

/** 打开草稿的可视化编辑器（草稿 stepsJson → 编辑器结构） */
function openDraftEditor() {
  if (!draft.value) return;
  draftEditing.value = true;
  editorSteps.value = parseStepsJson(draft.value.stepsJson);
}

/** 保存草稿编辑器：新 stepsJson 回写 draft，并把结构转回 DraftNode 树 */
function saveDraftEditor(stepsJson) {
  if (!draft.value) return;
  draft.value.stepsJson = stepsJson;
  draft.value.steps = parseStepsJson(stepsJson).map(editorNodeToDraft);
  draftEditing.value = false;
  showToast('草案已更新，可继续调整或创建');
}

/** 编辑器结构 → DraftNode 树结构（kind: step/parallel/if） */
function editorNodeToDraft(node) {
  if (node.parallel) {
    return { kind: 'parallel', parallelSteps: (node.parallel || []).map(editorNodeToDraft) };
  }
  if (node.if !== undefined) {
    return {
      kind: 'if',
      condition: node.if,
      thenBranch: (node.then || []).map(editorNodeToDraft),
      elseBranch: (node.else || []).map(editorNodeToDraft)
    };
  }
  return {
    kind: 'step',
    tool: node.tool,
    paramsJson: node.params && Object.keys(node.params).length ? JSON.stringify(node.params) : '{}',
    outputVar: node.outputVar || '',
    goal: ''
  };
}

/** 把后端 stepsJson（数组，含 parallel/if 嵌套）解析为编辑器结构对象 */
function parseStepsJson(json) {
  try {
    const arr = typeof json === 'string' ? JSON.parse(json) : json;
    return (Array.isArray(arr) ? arr : []).map(nodeFromJson);
  } catch (e) {
    return [];
  }
}

function nodeFromJson(node) {
  if (node.parallel) {
    return { parallel: (node.parallel || []).map(stepFromJson) };
  }
  if (node.if) {
    return {
      if: node.if,
      then: (node.then || []).map(nodeFromJson),
      else: (node.else || []).map(nodeFromJson)
    };
  }
  return stepFromJson(node);
}

function stepFromJson(node) {
  return {
    tool: node.tool || '',
    params: node.params || {},
    outputVar: node.output_var || ''
  };
}

function openEditor(w) {
  editorWorkflow.value = w;
  editorSteps.value = parseStepsJson(w.stepsJson);
}

async function saveEditor(stepsJson) {
  if (!editorWorkflow.value) return;
  savingEditor.value = true;
  try {
    await api(`/api/workflows/${editorWorkflow.value.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        name: editorWorkflow.value.name,
        description: editorWorkflow.value.description || '',
        stepsJson,
        triggerType: editorWorkflow.value.triggerType,
        cronExpr: editorWorkflow.value.cronExpr
      })
    });
    showToast('工作流已更新');
    editorWorkflow.value = null;
    editorSteps.value = [];
    await load();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    savingEditor.value = false;
  }
}

// 运行记录
const runs = ref([]);
const runsFor = ref(null);
const runDetail = ref(null);
const detailFor = ref(null);
const loadingRuns = ref(false);

async function load() {
  loading.value = true;
  try {
    workflows.value = await api('/api/workflows');
    await loadWebhookUrls();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

// webhook 工作流的调用 URL（token 单独查询）
const webhookUrls = ref({});
const origin = location.origin;

async function loadWebhookUrls() {
  const hooks = workflows.value.filter(w => w.triggerType === 'webhook');
  webhookUrls.value = {};
  for (const w of hooks) {
    try {
      const info = await api(`/api/workflows/${w.id}/webhook`);
      if (info.url) webhookUrls.value[w.id] = info.url;
    } catch (err) {
      // 忽略单条查询失败
    }
  }
}

async function copyWebhookUrl(id) {
  const url = webhookUrls.value[id];
  if (!url) return;
  try {
    await navigator.clipboard.writeText(location.origin + url);
    showToast('Webhook 地址已复制');
  } catch (err) {
    showToast('复制失败，请手动复制：' + url, true);
  }
}

async function generateDraft() {
  if (!description.value.trim()) {
    showToast('请描述你的需求', true);
    return;
  }
  generating.value = true;
  draft.value = null;
  monitorWorkflow.value = null;
  try {
    draft.value = await api('/api/workflows/generate', {
      method: 'POST',
      body: JSON.stringify({ description: description.value.trim() })
    });
  } catch (err) {
    showToast(err.message, true);
  } finally {
    generating.value = false;
  }
}

// ---------- 缺失能力反推（能力盘点）----------
async function analyzeCapabilities() {
  if (!description.value.trim()) {
    showToast('请先描述你的业务需求', true);
    return;
  }
  capAnalyzing.value = true;
  capAnalysis.value = null;
  try {
    capAnalysis.value = await api('/api/capabilities/analyze', {
      method: 'POST',
      body: JSON.stringify({ description: description.value.trim() })
    });
  } catch (err) {
    showToast(err.message, true);
  } finally {
    capAnalyzing.value = false;
  }
}

/** 由缺失能力清单生成可导入的 OpenAPI 片段 */
function openapiSnippet() {
  const gaps = capAnalysis.value?.gaps || [];
  const paths = {};
  for (const g of gaps) {
    if (!g.path) continue;
    paths[g.path] = {
      [String(g.method || 'POST').toLowerCase()]: {
        operationId: g.suggestedName || ('cap_' + g.path.replace(/[^a-zA-Z0-9]/g, '_')),
        summary: g.description || '补充缺失能力',
        tags: ['缺失能力']
      }
    };
  }
  return JSON.stringify({ openapi: '3.0.3', info: { title: '补充缺失能力', version: '1.0' }, paths }, null, 2);
}

async function copyOpenapiSnippet() {
  try {
    await navigator.clipboard.writeText(openapiSnippet());
    showToast('缺失能力 OpenAPI 片段已复制，可在「接口」页导入');
  } catch (err) {
    showToast('复制失败，请手动复制', true);
  }
}

async function createWorkflow() {
  if (!draft.value) return;
  const name = workflowName.value.trim() || description.value.trim().slice(0, 30);
  creating.value = true;
  try {
    const w = await api('/api/workflows', {
      method: 'POST',
      body: JSON.stringify({
        name,
        description: description.value.trim(),
        stepsJson: draft.value.stepsJson,
        triggerType: triggerType.value,
        cronExpr: triggerType.value === 'cron' ? cronExpr.value.trim() : undefined
      })
    });
    showToast(`工作流 ${w.name} 已创建`);
    draft.value = null;
    workflowName.value = '';
    description.value = '';
    cronExpr.value = '';
    triggerType.value = 'manual';
    await load();
  } catch (err) {
    showToast(err.message, true);
  } finally {
    creating.value = false;
  }
}

async function runWorkflow(w) {
  if (!confirm(`确认运行工作流「${w.name}」？`)) return;
  // 打开运行监视器：SSE 实时步骤 + 审批节点（human-in-the-loop）
  monitorWorkflow.value = w;
}

async function showRuns(w) {
  runsFor.value = w.id;
  detailFor.value = null;
  runDetail.value = null;
  loadingRuns.value = true;
  runs.value = [];
  try {
    runs.value = await api(`/api/workflows/${w.id}/runs?limit=10`);
  } catch (err) {
    showToast(err.message, true);
  } finally {
    loadingRuns.value = false;
  }
}

// 另存为技能（Guide-51 P1）：工作流 → LLM 生成规范草稿 → 编辑确认 → 创建团队技能
const skillDraft = ref(null);
const skillSaving = ref(false);

async function draftAsSkill(w) {
  skillDraft.value = null;
  try {
    const draft = await api(`/api/skills/from-workflow/${w.id}`, { method: 'POST' });
    skillDraft.value = {
      scope: 'team',
      name: draft.name,
      description: draft.description || '',
      applyTo: draft.applyTo || '',
      content: draft.content,
      references: draft.references || '[]',
      sourceWorkflowId: draft.sourceWorkflowId
    };
  } catch (err) {
    showToast(err.message, true);
  }
}

async function saveSkillDraft() {
  if (!skillDraft.value.name.trim()) { showToast('请输入技能名称', true); return; }
  if (!skillDraft.value.content.trim()) { showToast('请输入技能内容', true); return; }
  skillSaving.value = true;
  try {
    await api('/api/skills', {
      method: 'POST',
      body: JSON.stringify({
        scope: skillDraft.value.scope,
        name: skillDraft.value.name.trim(),
        description: skillDraft.value.description || '',
        applyTo: skillDraft.value.applyTo || '',
        content: skillDraft.value.content,
        references: skillDraft.value.references || '[]'
      })
    });
    showToast('技能已创建，Agent 遇到同类任务会自动遵循');
    skillDraft.value = null;
  } catch (err) {
    showToast(err.message, true);
  } finally {
    skillSaving.value = false;
  }
}

async function showDetail(runId) {
  try {
    runDetail.value = await api(`/api/workflows/runs/${runId}`);
    detailFor.value = runId;
  } catch (err) {
    showToast(err.message, true);
  }
}

async function deleteWorkflow(w) {
  if (!confirm(`确认删除工作流「${w.name}」？`)) return;
  try {
    await api(`/api/workflows/${w.id}`, { method: 'DELETE' });
    showToast('工作流已删除');
    await load();
  } catch (err) {
    showToast(err.message, true);
  }
}

onMounted(load);
</script>

<template>
  <section class="wf-grid">
    <div class="panel">
      <h2>创建流程（说人话）</h2>
      <label>描述需求
        <textarea v-model="description" rows="3" placeholder="例如：查一下监控系统的版本信息，然后用一句话总结运行状态"></textarea>
      </label>
      <div class="row-actions">
        <button class="primary" :disabled="generating" @click="generateDraft">
          {{ generating ? '生成中…' : '生成流程草案' }}
        </button>
        <button :disabled="capAnalyzing" @click="analyzeCapabilities">
          {{ capAnalyzing ? '盘点中…' : '🧭 能力盘点' }}
        </button>
      </div>
      <p class="hint sub">能力盘点：先看现有接口能否满足需求，缺什么一目了然。</p>

      <div v-if="capAnalysis" class="cap-panel">
        <h3>能力盘点：现有接口 {{ capAnalysis.matchedInterfaces.length }} 个命中 · 缺失能力 {{ capAnalysis.gaps.length }} 个</h3>
        <template v-if="capAnalysis.matchedInterfaces.length">
          <p class="cap-label">现有接口（可满足部分需求）：</p>
          <ul class="cap-list">
            <li v-for="m in capAnalysis.matchedInterfaces" :key="m.name">
              <b>{{ m.name }}</b>
              <span class="cap-desc">{{ m.description || '' }}</span>
              <span class="cap-score">{{ (m.score * 100).toFixed(0) }}%</span>
            </li>
          </ul>
        </template>
        <template v-if="capAnalysis.steps.length">
          <p class="cap-label">需求拆解：</p>
          <ol class="cap-steps">
            <li v-for="(s, i) in capAnalysis.steps" :key="i" :class="s.covered ? 'covered' : 'missing'">
              <span class="cap-mark">{{ s.covered ? '✓' : '✗' }}</span>
              <span>{{ s.step }}</span>
              <span v-if="s.covered && s.interfaceName" class="cap-iface">→ {{ s.interfaceName }}</span>
              <span v-if="!s.covered && s.gap" class="cap-gap">需要 {{ s.gap.suggestedName }}（{{ s.gap.method }} {{ s.gap.path }}）</span>
            </li>
          </ol>
        </template>
        <template v-if="capAnalysis.gaps.length">
          <p class="cap-label">缺失能力清单（补全后可完成整个需求）：</p>
          <ul class="cap-list">
            <li v-for="g in capAnalysis.gaps" :key="g.suggestedName">
              <b>{{ g.suggestedName }}</b>
              <span class="cap-desc">{{ g.method }} {{ g.path }} — {{ g.description }}</span>
            </li>
          </ul>
          <button class="small" @click="copyOpenapiSnippet">📋 复制缺失能力 OpenAPI 片段（去「接口」页导入）</button>
        </template>
        <p v-if="capAnalysis.warnings.length" class="hint sub">提示：{{ capAnalysis.warnings.join('；') }}</p>
      </div>

      <div v-if="draft" class="draft">
        <h3>
          草案（{{ draft.steps.length }} 步，支持顺序/分支/并行）
          <span v-for="b in draftBadges()" :key="b.text" class="draft-badge" :class="b.cls">{{ b.text }}</span>
        </h3>
        <div class="draft-tree">
          <DraftNode v-for="(s, i) in draft.steps" :key="i" :node="s" :index="i" />
        </div>
        <label>流程名称（可选）
          <input v-model="workflowName" placeholder="留空则用需求前 30 字">
        </label>
        <label>触发方式
          <select v-model="triggerType">
            <option value="manual">手动运行</option>
            <option value="cron">定时运行</option>
            <option value="webhook">Webhook 调用</option>
          </select>
        </label>
        <label v-if="triggerType === 'cron'">cron 表达式
          <input v-model="cronExpr" placeholder="例如：0 0 9 * * *（每天 09:00）">
        </label>
        <p v-else-if="triggerType === 'webhook'" class="hint">创建后将生成调用地址，外部系统 POST 即可触发此工作流。</p>
        <div class="draft-actions">
          <button class="primary" :disabled="creating" @click="createWorkflow">
            {{ creating ? '创建中…' : '确认创建流程' }}
          </button>
          <button :disabled="creating" @click="openDraftEditor">✏️ 可视化调整草稿</button>
        </div>
      </div>
      <div v-if="monitorWorkflow" class="draft">
        <WorkflowRunMonitor
          :workflow="monitorWorkflow"
          @close="monitorWorkflow = null; load()"
        />
      </div>

      <div v-if="skillDraft" class="draft">
        <h3>存为技能（编辑后保存，Agent 遇到同类任务自动遵循）</h3>
        <label>技能名称
          <input v-model="skillDraft.name">
        </label>
        <label>适用范围
          <select v-model="skillDraft.scope">
            <option value="team">团队（全员生效）</option>
            <option value="personal">个人（仅自己）</option>
          </select>
        </label>
        <label>触发关键词（| 分隔）
          <input v-model="skillDraft.applyTo" placeholder="如：月报|经营分析|月度报告">
        </label>
        <label>技能内容（AI 生成的规范，可编辑）
          <textarea v-model="skillDraft.content" rows="6"></textarea>
        </label>
        <button class="primary" :disabled="skillSaving" @click="saveSkillDraft">
          {{ skillSaving ? '保存中…' : '保存技能' }}
        </button>
        <button @click="skillDraft = null">取消</button>
      </div>
    </div>

    <div class="panel scroll-panel">
      <h2>我的流程（{{ workflows.length }}）</h2>
      <div v-if="loading" class="skeleton-list" aria-label="加载中">
        <div v-for="i in 4" :key="i" class="skeleton-row">
          <div class="skeleton-block lg"></div>
          <div class="skeleton-block md"></div>
          <div class="skeleton-block sm"></div>
        </div>
      </div>
      <div v-else-if="!workflows.length" class="empty">还没有流程。左侧描述需求，AI 帮你生成。</div>
      <template v-else>
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>名称</th><th>描述</th><th>触发</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="w in workflows" :key="w.id">
                <td><b>{{ w.name }}</b></td>
                <td class="desc">{{ w.description || '—' }}</td>
                <td class="trigger">
                {{ w.triggerType === 'cron' ? '定时 ' + (w.cronExpr || '') : (w.triggerType === 'webhook' ? 'Webhook' : '手动') }}
                <span v-if="w.triggerType === 'webhook' && webhookUrls[w.id]" class="webhook-url" :title="origin + webhookUrls[w.id]">
                  {{ webhookUrls[w.id] }}
                  <button class="small" @click.stop="copyWebhookUrl(w.id)">复制</button>
                </span>
              </td>
                <td><span class="status" :class="w.status">{{ wfStatusLabel(w.status) }}</span></td>
                <td>
                  <button class="small" :disabled="runningId === w.id || w.status !== 'ENABLED'" @click="runWorkflow(w)">{{ runningId === w.id ? '运行中…' : '运行' }}</button>
                  <button class="small" @click="openEditor(w)">编辑</button>
                  <button class="small" @click="showRuns(w)">记录</button>
                  <button class="small" @click="draftAsSkill(w)">存为技能</button>
                  <button class="small danger" @click="deleteWorkflow(w)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 可视化编辑器（Guide-55 高优先级）：树形编排 stepsJson -->
        <WorkflowEditor
          v-if="editorWorkflow"
          :steps="editorSteps"
          :key="editorWorkflow.id"
          :disabled="savingEditor"
          @save="saveEditor"
          @cancel="editorWorkflow = null; editorSteps = []"
        />

        <!-- 草稿可视化调整（对话生成 parallel 深度引导） -->
        <WorkflowEditor
          v-else-if="draftEditing && draft"
          :steps="editorSteps"
          :key="'draft-' + draft.stepsJson.length"
          @save="saveDraftEditor"
          @cancel="draftEditing = false; editorSteps = []"
        />

        <div v-if="runsFor !== null" class="runs">
          <h3>运行记录</h3>
          <div v-if="loadingRuns" class="empty">加载中…</div>
          <div v-else-if="!runs.length" class="empty">暂无运行记录</div>
          <div v-else class="table-wrap">
            <table>
              <thead><tr><th>#</th><th>状态</th><th>触发</th><th>错误</th><th></th></tr></thead>
              <tbody>
                <tr v-for="r in runs" :key="r.id">
                  <td>{{ r.id }}</td>
                  <td><span class="status" :class="r.status">{{ wfStatusLabel(r.status) }}</span></td>
                  <td>{{ r.triggerType }}</td>
                  <td class="err">{{ r.error || '—' }}</td>
                  <td><button class="small" @click="showDetail(r.id)">步骤</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="runDetail && detailFor" class="runs">
          <h3>运行 #{{ detailFor }} 步骤明细</h3>
          <div v-for="(s, i) in runDetail.steps" :key="i" class="step-row" :class="s.status.toLowerCase()">
            <div class="head">
              <span class="no">{{ i + 1 }}</span>
              <b>{{ s.toolName }}</b>
              <span class="status" :class="s.status">{{ wfStatusLabel(s.status) }}</span>
              <span class="cost">{{ s.costMs }}ms</span>
            </div>
            <div v-if="s.error" class="err">错误：{{ s.error }}</div>
            <!-- 长输出默认折叠：避免巨型 JSON（如 kb_search 检索结果）一次性全量渲染拖垮页面 -->
            <details v-if="s.outputJson && s.outputJson.length > 400" class="out">
              <summary>输出（{{ s.outputJson.length }} 字符）</summary>
              <pre class="out-body">{{ s.outputJson }}</pre>
            </details>
            <pre v-else-if="s.outputJson" class="out">{{ s.outputJson }}</pre>
          </div>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.wf-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 400px 1fr;
  align-items: start;
}

.wf-grid .panel {
  display: grid;
  gap: 10px;
}

/* 右侧"我的流程"：内容多时面板内滚动，不撑高整个页面 */
.scroll-panel {
  height: calc(100vh - 110px);
  overflow-y: auto;
  align-content: start;
}

@media (max-width: 900px) {
  .scroll-panel {
    height: auto;
    overflow: visible;
  }
}

.draft {
  border-top: 1px dashed var(--line);
  padding-top: 12px;
  display: grid;
  gap: 8px;
}

/* 能力盘点面板（缺失能力反推） */
.row-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.cap-panel {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  display: grid;
  gap: 6px;
  background: var(--bg-soft, #f8f9fa);
}

.cap-panel h3 {
  margin: 0;
  font-size: 14px;
}

.cap-label {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted, #888);
}

.cap-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  display: grid;
  gap: 3px;
}

.cap-list li {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.cap-desc {
  color: var(--muted, #888);
  flex: 1;
}

.cap-score {
  color: var(--ok, #1a7f37);
  font-size: 12px;
}

.cap-steps {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  display: grid;
  gap: 3px;
}

.cap-steps li {
  display: flex;
  gap: 6px;
  align-items: baseline;
}

.cap-steps li.missing {
  color: var(--danger, #d1242f);
}

.cap-mark {
  font-weight: bold;
}

.cap-iface {
  color: var(--ok, #1a7f37);
}

.cap-gap {
  color: var(--danger, #d1242f);
}

/* 草案结构徽标（对话生成 parallel 产品化） */
.draft-badge {
  font-size: 11px;
  font-weight: normal;
  padding: 1px 8px;
  border-radius: 10px;
  margin-left: 6px;
  vertical-align: middle;
}
.badge-parallel { background: #ede7f6; color: #4527a0; }
.badge-branch { background: #e0f2f1; color: #00695c; }

/* 草稿操作区：确认创建 + 可视化调整 */
.draft-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--muted, #888);
}

.webhook-url {
  display: block;
  font-size: 11px;
  color: var(--muted, #888);
  font-family: var(--mono, monospace);
  word-break: break-all;
  margin-top: 2px;
}

.draft-step {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 8px;
}

.no {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.draft-step .body {
  font-size: 13px;
  display: grid;
  gap: 2px;
}

/* 业务人员视角：步骤标题（业务化 goal），技术细节折叠展示 */
.step-title {
  font-weight: 600;
}

.step-detail {
  font-size: 12px;
  color: var(--muted, #888);
  margin-top: 2px;
}

.step-detail summary {
  cursor: pointer;
  user-select: none;
  opacity: 0.8;
}

.detail-inner {
  display: grid;
  gap: 2px;
  margin-top: 4px;
  padding: 4px 6px;
  border-left: 2px solid var(--line);
  word-break: break-all;
}

.detail-inner code {
  font-family: var(--mono, monospace);
  background: var(--line, #eee);
  padding: 0 3px;
  border-radius: 3px;
}

.draft-step .params, .draft-step .out {
  color: var(--muted);
  font-size: 12px;
  font-family: var(--mono, monospace);
  word-break: break-all;
}

.draft-tree {
  display: grid;
  gap: 8px;
}

.draft-branch {
  display: grid;
  gap: 6px;
  border: 1px dashed var(--line);
  border-radius: 6px;
  padding: 8px;
  background: var(--alt-bg);
}

.branch-head {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
}

.branch-label {
  font-size: 12px;
  color: var(--muted);
  margin: 4px 0 2px;
}

.branch-body {
  display: grid;
  gap: 6px;
  padding-left: 10px;
  border-left: 2px solid var(--line);
}

.run-result {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 8px 10px;
  background: var(--alt-bg);
}

.run-result pre {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 4px 0 0;
  font-size: 13px;
}

td.desc {
  max-width: 220px;
}

td.err {
  color: var(--danger);
  max-width: 200px;
}

.runs {
  border-top: 1px dashed var(--line);
  margin-top: 10px;
  padding-top: 10px;
  display: grid;
  gap: 6px;
}

.step-row {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 8px;
  display: grid;
  gap: 4px;
}

.step-row .head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.step-row .out {
  font-size: 12px;
  color: var(--muted);
  font-family: var(--mono, monospace);
  word-break: break-word;
  max-height: 120px;
  overflow-y: auto;
}

/* 长输出折叠（details）：summary 可点击展开，巨型 JSON 默认不渲染 */
.step-row details.out summary {
  cursor: pointer;
  color: #1565c0;
  font-size: 12px;
}
.step-row details.out pre.out-body {
  font-size: 12px;
  color: var(--muted);
  font-family: var(--mono, monospace);
  word-break: break-word;
  white-space: pre-wrap;
  max-height: 240px;
  overflow-y: auto;
  margin-top: 6px;
}

.cost {
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
}

button.danger {
  color: var(--danger);
  border-color: var(--danger);
}

@media (max-width: 900px) {
  .wf-grid {
    grid-template-columns: 1fr;
  }
}
</style>
