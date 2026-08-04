<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { api, getToken, getCurrentUserId } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';
import { kbsStore } from '@/stores/kbs';
import KbSidebar from '@/components/KbSidebar.vue';
import MemberPanel from '@/components/MemberPanel.vue';
import DocTable from '@/components/DocTable.vue';

const currentKbId = ref(null);
const currentKbTitle = computed(() => {
  const kb = kbsStore.kbs.find((k) => k.id === currentKbId.value);
  return kb ? kb.name : '';
});
const uploadTags = ref('');
const docs = ref([]);
const teams = ref([]);
const pollTimer = ref(null);
const fileInput = ref(null);

async function loadTeams() {
  try {
    const data = await api('/api/teams');
    teams.value = data.items || [];
  } catch (err) {
    teams.value = [];
  }
}

async function loadKbs() {
  try {
    await kbsStore.load();
  } catch (err) {
    showToast(err.message, true);
  }
  if (kbsStore.kbs.length && !currentKbId.value) {
    await selectKb(kbsStore.kbs[0].id);
  } else if (!kbsStore.kbs.length) {
    currentKbId.value = null;
    docs.value = [];
  }
}

async function selectKb(id) {
  currentKbId.value = id;
  await Promise.all([loadDocs(), loadMembersSignal()]);
}

// 触发 MemberPanel 重新加载
const memberReloadKey = ref(0);
async function loadMembersSignal() {
  memberReloadKey.value += 1;
}

async function loadDocs() {
  if (!currentKbId.value) {
    docs.value = [];
    return;
  }
  try {
    const data = await api(`/api/knowledge-bases/${currentKbId.value}/documents?page=1&pageSize=100`);
    docs.value = data.items || [];
    schedulePoll();
  } catch (err) {
    showToast(err.message, true);
  }
}

function schedulePoll() {
  if (pollTimer.value) {
    clearInterval(pollTimer.value);
  }
  pollTimer.value = setInterval(async () => {
    const hasPending = docs.value.some((d) => d.status === 'UPLOADED' || d.status === 'PROCESSING');
    if (hasPending && currentKbId.value) {
      await loadDocs();
    }
  }, 2000);
}

function pickFile() {
  fileInput.value?.click();
}

async function onFileChange(event) {
  const files = Array.from(event.target.files || []);
  if (!files.length) {
    return;
  }
  const form = new FormData();
  files.forEach((file) => form.append('files', file));
  if (uploadTags.value) {
    form.append('tags', uploadTags.value);
  }
  try {
    const res = await api(`/api/knowledge-bases/${currentKbId.value}/documents/batch`, {
      method: 'POST',
      body: form
    });
    const failed = res.failed || 0;
    showToast(
      failed > 0
        ? `批量上传 ${res.total} 个，失败 ${failed} 个`
        : `批量上传 ${res.total} 个文件成功`
    );
    uploadTags.value = '';
    await loadDocs();
  } catch (err) {
    showToast(err.message, true);
  }
  event.target.value = '';
}

async function exportKb() {
  if (!currentKbId.value) {
    return;
  }
  try {
    const headers = {
      'X-User-Id': String(getCurrentUserId()),
      'Authorization': getToken() ? 'Bearer ' + getToken() : ''
    };
    const res = await fetch(`/api/knowledge-bases/${currentKbId.value}/export`, { headers });
    if (!res.ok) {
      showToast('导出失败', true);
      return;
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `kb-${currentKbId.value}.zip`;
    link.click();
    URL.revokeObjectURL(url);
    showToast('知识库已导出');
  } catch (err) {
    showToast(err.message, true);
  }
}

async function onKbCreated(kb) {
  await loadKbs();
  await selectKb(kb.id);
}

watch(
  () => session.reloadKey,
  () => {
    currentKbId.value = null;
    loadKbs();
    loadTeams();
  }
);

onMounted(() => {
  loadKbs();
  loadTeams();
});
onBeforeUnmount(() => {
  if (pollTimer.value) clearInterval(pollTimer.value);
});
</script>

<template>
  <section class="kb-grid">
    <KbSidebar :kbs="kbsStore.kbs" :current-kb-id="currentKbId" :teams="teams" @select="selectKb" @created="onKbCreated" />
    <div class="panel kb-main">
      <div class="panel-header">
        <h2>{{ currentKbId ? currentKbTitle : '请选择知识库' }}</h2>
        <div class="actions">
          <input v-if="currentKbId" v-model="uploadTags" placeholder="标签（逗号分隔，可选）" class="tags-input">
          <button v-if="currentKbId" class="primary" @click="pickFile">上传（可多选）</button>
          <button v-if="currentKbId" class="secondary" @click="exportKb">导出</button>
          <input ref="fileInput" type="file" accept=".md,.markdown,.pdf" multiple hidden @change="onFileChange">
        </div>
      </div>
      <DocTable :docs="docs" :kb-id="currentKbId" @refresh="loadDocs" />
    </div>
    <MemberPanel :key="memberReloadKey" :kb-id="currentKbId" />
  </section>
</template>

<style scoped>
.kb-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 300px 1fr;
  align-items: start;
}

.kb-main {
  min-width: 0;
}

.tags-input {
  width: 200px;
}

@media (max-width: 900px) {
  .kb-grid {
    grid-template-columns: 1fr;
  }
}
</style>
