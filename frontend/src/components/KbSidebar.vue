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
      <div v-if="!kbs.length" class="empty small">还没有知识库</div>
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
