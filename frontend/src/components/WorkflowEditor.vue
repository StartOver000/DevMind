<script setup>
import { ref, computed } from 'vue';

/**
 * 工作流可视化编辑器（Guide-55 高优先级）：
 * 树形编辑 stepsJson（顺序步骤 / 并行组 / if 条件分支），业务人员零代码编排。
 * 直接编辑结构对象，保存时序列化为 stepsJson 交给后端。
 *
 * 节点结构（与后端 WorkflowExecutor 对齐）：
 *   { tool, params, outputVar }                    顺序步骤
 *   { parallel: [step, ...] }                      并行组
 *   { if: 条件, then: [...], else: [...] }         条件分支（递归）
 */

const props = defineProps({
  steps: { type: Array, required: true }   // 已解析的结构对象数组
});
const emit = defineEmits(['save', 'cancel']);

const editing = ref(null); // 正在编辑的节点（含位置路径）

// 可用工具列表（由父组件注入，或内部维护）
const tools = ref([
  'prom_buildinfo', 'ai_generate', 'kb_search', 'kb_info',
  'doc_search', 'doc_list', 'sql_diagnose', 'usage_query', 'usage_stats'
]);

function nodeLabel(n) {
  if (n.parallel) return `并行（${n.parallel.length} 步）`;
  if (n.if !== undefined) return `如果 ${n.if}`;
  return n.tool || '未命名步骤';
}

function newNode() {
  return { tool: tools.value[0] || '', params: {}, outputVar: '' };
}

function addStep(list) {
  list.push(newNode());
}

function removeAt(list, idx) {
  if (confirm('确认删除该步骤/分支？')) list.splice(idx, 1);
}

function move(list, idx, dir) {
  const target = idx + dir;
  if (target < 0 || target >= list.length) return;
  [list[idx], list[target]] = [list[target], list[idx]];
}

function toParallel(list, idx) {
  const node = list[idx];
  if (node.parallel) return;
  list[idx] = { parallel: [{ ...node }] };
}

function fromParallel(list, idx) {
  const node = list[idx];
  if (!node.parallel || !node.parallel.length) return;
  list[idx] = { ...node.parallel[0] };
}

function addIfBranch(list) {
  list.push({ if: '', then: [newNode()], else: [] });
}

function addParallelGroup(list) {
  list.push({ parallel: [newNode(), newNode()] });
}

// 编辑表单绑定
const editForm = ref({});
const editPath = ref(null); // 数组路径，用于写回

function openEdit(node, path) {
  editForm.value = {
    tool: node.tool || '',
    params: JSON.stringify(node.params || {}, null, 2),
    outputVar: node.outputVar || ''
  };
  editPath.value = { node, path };
  editing.value = node;
}

function saveEdit() {
  if (!editPath.value) return;
  const { node } = editPath.value;
  let params = {};
  try {
    params = editForm.value.params.trim() ? JSON.parse(editForm.value.params) : {};
  } catch (e) {
    alert('参数不是合法 JSON，请检查');
    return;
  }
  node.tool = editForm.value.tool.trim();
  node.params = params;
  node.outputVar = editForm.value.outputVar.trim();
  delete node.parallel;
  delete node.if;
  editing.value = null;
  editPath.value = null;
}

// 序列化回后端 stepsJson 格式（工具参数用 params 对象）
function serialize(list) {
  return list.map(nodeToJson);
}

function nodeToJson(node) {
  if (node.parallel) {
    return { parallel: node.parallel.map(nodeToStep) };
  }
  if (node.if !== undefined) {
    return {
      if: node.if,
      then: node.then.map(nodeToJson),
      else: (node.else || []).map(nodeToJson)
    };
  }
  return nodeToStep(node);
}

function nodeToStep(node) {
  const step = { tool: node.tool };
  if (node.params && Object.keys(node.params).length) step.params = node.params;
  if (node.outputVar) step.output_var = node.outputVar;
  return step;
}

const previewJson = computed(() => JSON.stringify(serialize(props.steps), null, 2));

function handleSave() {
  emit('save', previewJson.value);
}
</script>

<template>
  <div class="wf-editor">
    <div class="editor-head">
      <h3>可视化编排</h3>
      <div class="head-actions">
        <button class="primary" @click="handleSave">保存</button>
        <button @click="emit('cancel')">取消</button>
      </div>
    </div>

    <div class="editor-body">
      <!-- 步骤树 -->
      <div class="tree">
        <div class="step-list">
          <div v-for="(node, i) in steps" :key="i" class="step-item">
            <!-- 并行组 -->
            <div v-if="node.parallel" class="branch parallel">
              <div class="branch-head">
                <span class="badge">⚡ 并行组</span>
                <div class="ops">
                  <button class="tiny" @click="openEdit(node, [i])">改名</button>
                  <button class="tiny" @click="fromParallel(steps, i)">转为单步</button>
                  <button class="tiny" @click="move(steps, i, -1)" :disabled="i === 0">↑</button>
                  <button class="tiny" @click="move(steps, i, 1)" :disabled="i === steps.length - 1">↓</button>
                  <button class="tiny danger" @click="removeAt(steps, i)">✕</button>
                </div>
              </div>
              <div class="branch-body">
                <div v-for="(s, si) in node.parallel" :key="si" class="step-card">
                  <span class="step-tool">{{ s.tool || '未选工具' }}</span>
                  <span class="step-var" v-if="s.outputVar">→ {{ s.outputVar }}</span>
                  <button class="tiny" @click="openEdit(s, ['parallel', i, si])">编辑</button>
                </div>
                <button class="tiny add" @click="node.parallel.push(newNode())">+ 并行步骤</button>
              </div>
            </div>

            <!-- 条件分支 -->
            <div v-else-if="node.if !== undefined" class="branch ifbranch">
              <div class="branch-head">
                <span class="badge">🔀 如果 <code>{{ node.if || '条件未填' }}</code></span>
                <div class="ops">
                  <button class="tiny" @click="openEdit(node, [i])">改条件</button>
                  <button class="tiny" @click="move(steps, i, -1)" :disabled="i === 0">↑</button>
                  <button class="tiny" @click="move(steps, i, 1)" :disabled="i === steps.length - 1">↓</button>
                  <button class="tiny danger" @click="removeAt(steps, i)">✕</button>
                </div>
              </div>
              <div class="branch-body">
                <div class="branch-col">
                  <span class="branch-label">满足时（then）</span>
                  <div v-for="(s, si) in node.then" :key="'t' + si" class="step-card">
                    <span class="step-tool">{{ s.tool || '未选工具' }}</span>
                    <span class="step-var" v-if="s.outputVar">→ {{ s.outputVar }}</span>
                    <button class="tiny" @click="openEdit(s, ['if', i, 'then', si])">编辑</button>
                    <button class="tiny danger" @click="node.then.splice(si, 1)">✕</button>
                  </div>
                  <button class="tiny add" @click="node.then.push(newNode())">+ then 步骤</button>
                </div>
                <div class="branch-col" v-if="node.else !== undefined">
                  <span class="branch-label">否则（else）</span>
                  <div v-for="(s, si) in node.else" :key="'e' + si" class="step-card">
                    <span class="step-tool">{{ s.tool || '未选工具' }}</span>
                    <span class="step-var" v-if="s.outputVar">→ {{ s.outputVar }}</span>
                    <button class="tiny" @click="openEdit(s, ['if', i, 'else', si])">编辑</button>
                    <button class="tiny danger" @click="node.else.splice(si, 1)">✕</button>
                  </div>
                  <button class="tiny add" @click="node.else.push(newNode())">+ else 步骤</button>
                </div>
              </div>
            </div>

            <!-- 普通步骤 -->
            <div v-else class="step-card">
              <span class="step-no">{{ i + 1 }}</span>
              <span class="step-tool">{{ node.tool || '未选工具' }}</span>
              <span class="step-var" v-if="node.outputVar">→ {{ node.outputVar }}</span>
              <div class="ops">
                <button class="tiny" @click="toParallel(steps, i)">⚡并行</button>
                <button class="tiny" @click="openEdit(node, [i])">编辑</button>
                <button class="tiny" @click="move(steps, i, -1)" :disabled="i === 0">↑</button>
                <button class="tiny" @click="move(steps, i, 1)" :disabled="i === steps.length - 1">↓</button>
                <button class="tiny danger" @click="removeAt(steps, i)">✕</button>
              </div>
            </div>
          </div>
        </div>

        <div class="tree-actions">
          <button class="tiny add" @click="addStep(steps)">+ 添加步骤</button>
          <button class="tiny add" @click="addParallelGroup(steps)">+ 并行组</button>
          <button class="tiny add" @click="addIfBranch(steps)">+ 条件分支</button>
        </div>
      </div>

      <!-- 编辑面板 + JSON 预览 -->
      <div class="side">
        <div v-if="editing" class="edit-panel">
          <h4>编辑节点</h4>
          <label>工具
            <input v-model="editForm.tool" list="wf-tools">
          </label>
          <datalist id="wf-tools">
            <option v-for="t in tools" :key="t" :value="t" />
          </datalist>
          <label>参数（JSON）
            <textarea v-model="editForm.params" rows="5" placeholder='{"question":"xxx"}'></textarea>
          </label>
          <label>输出变量（可选）
            <input v-model="editForm.outputVar" placeholder="如：result">
          </label>
          <div class="ops">
            <button class="primary" @click="saveEdit">确定</button>
            <button @click="editing = null">取消</button>
          </div>
        </div>

        <details class="json-preview">
          <summary>JSON 预览</summary>
          <pre>{{ previewJson }}</pre>
        </details>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wf-editor {
  display: grid;
  gap: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 14px;
  background: var(--panel);
  margin-top: 10px;
}

.editor-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.editor-head h3 { margin: 0; }

.head-actions { display: flex; gap: 8px; }

.editor-body {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 16px;
  align-items: start;
}

.step-list { display: grid; gap: 8px; }

.step-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--alt-bg);
}

.step-no {
  width: 20px; height: 20px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}

.step-tool { font-weight: 600; font-size: 13px; }
.step-var { color: var(--muted); font-size: 12px; flex: 1; }

.ops { display: flex; gap: 4px; margin-left: auto; flex-wrap: wrap; }

.branch {
  border: 1px dashed var(--line);
  border-radius: 8px;
  padding: 10px;
  display: grid;
  gap: 8px;
}

.branch-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.badge {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent);
}

.badge code { background: var(--alt-bg); padding: 1px 6px; border-radius: 4px; }

.branch-body {
  display: grid;
  gap: 6px;
}

.branch-col {
  display: grid;
  gap: 6px;
  padding: 6px;
  background: var(--bg-soft);
  border-radius: 6px;
}

.branch-label { font-size: 11px; color: var(--muted); }

.tree-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

button.tiny {
  padding: 2px 8px;
  font-size: 11px;
}

button.tiny.danger { color: var(--danger); }

button.tiny.add { color: var(--accent); }

.edit-panel {
  display: grid;
  gap: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px;
}

.edit-panel h4 { margin: 0; font-size: 14px; }

.json-preview pre {
  background: var(--alt-bg);
  padding: 8px;
  border-radius: 6px;
  font-size: 11px;
  overflow: auto;
  max-height: 240px;
}

@media (max-width: 1000px) {
  .editor-body { grid-template-columns: 1fr; }
}
</style>
