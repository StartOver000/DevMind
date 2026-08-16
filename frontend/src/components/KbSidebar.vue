<script setup>
import { ref } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';

const props = defineProps({
  kbs: { type: Array, default: () => [] },
  currentKbId: { type: Number, default: null },
  teams: { type: Array, default: () => [] }
});
const emit = defineEmits(['select', 'created']);

const kbName = ref('');
const kbDesc = ref('');
const kbTeamId = ref('');
const creating = ref(false);
const creatingDemo = ref(false);

// 冷启动：一键创建内置示例知识库（产品运营盲区修复，见 docs/product/产品审视2-运营体验盲区-20260816.md）
async function createDemoKb() {
  creatingDemo.value = true;
  try {
    const kb = await api('/api/knowledge-bases/demo', { method: 'POST' });
    kbName.value = '';
    kbDesc.value = '';
    kbTeamId.value = '';
    emit('created', kb);
    showToast(kb.duplicate ? '示例知识库已存在' : '示例知识库已创建，文档向量化中…');
  } catch (err) {
    showToast(err.message, true);
  } finally {
    creatingDemo.value = false;
  }
}

async function createKb(event) {
  event.preventDefault();
  const name = kbName.value.trim();
  if (!name) {
    showToast('请输入知识库名称', true);
    return;
  }
  creating.value = true;
  try {
    const teamId = kbTeamId.value ? Number(kbTeamId.value) : null;
    const kb = await api('/api/knowledge-bases', {
      method: 'POST',
      body: JSON.stringify({ name, description: kbDesc.value.trim() || null, teamId })
    });
    kbName.value = '';
    kbDesc.value = '';
    kbTeamId.value = '';
    emit('created', kb);
    showToast('知识库创建成功');
  } catch (err) {
    showToast(err.message, true);
  } finally {
    creating.value = false;
  }
}
</script>

<template>
  <aside class="panel kb-side">
    <h2>知识库</h2>
    <form class="form-stack" @submit="createKb">
      <input v-model="kbName" maxlength="100" placeholder="知识库名称" required>
      <input v-model="kbDesc" maxlength="500" placeholder="描述（可选）">
      <select v-model="kbTeamId">
        <option value="">归属：个人（不选团队）</option>
        <option v-for="t in teams" :key="t.id" :value="t.id">团队：{{ t.name }}</option>
      </select>
      <button type="submit" class="primary" :disabled="creating">
        {{ creating ? '创建中…' : '创建知识库' }}
      </button>
    </form>
    <div class="kb-list">
      <div v-if="!kbs.length" class="empty small">还没有知识库，先体验一下👇</div>
      <button
        v-if="!kbs.length"
        class="demo-kb-btn"
        :disabled="creatingDemo"
        @click="createDemoKb"
      >
        {{ creatingDemo ? '创建中…' : '✨ 一键创建示例知识库（含示例文档）' }}
      </button>
      <button
        v-for="kb in kbs"
        :key="kb.id"
        class="kb-item"
        :class="{ active: kb.id === currentKbId }"
        @click="emit('select', kb.id)"
      >
        <span class="kb-name">{{ kb.name }}</span>
        <span class="kb-count">{{ kb.documentCount }} 文档</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.kb-side {
  display: grid;
  align-content: start;
  gap: 0;
}

.kb-list {
  display: grid;
  gap: 8px;
}

.demo-kb-btn {
  padding: 10px 12px;
  text-align: left;
  border: 1px dashed var(--accent);
  background: var(--accent-weak);
  color: var(--accent);
  font-weight: 600;
  cursor: pointer;
  border-radius: 8px;
}

.demo-kb-btn:hover:not(:disabled) {
  background: var(--accent-soft, var(--accent-weak));
}

.demo-kb-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.kb-item {
  display: grid;
  gap: 2px;
  text-align: left;
  padding: 10px 12px;
}

.kb-item.active {
  border-color: var(--accent);
  background: var(--accent-weak);
}

.kb-name {
  font-weight: 600;
}

.kb-count {
  color: var(--muted);
  font-size: 12px;
}
</style>
