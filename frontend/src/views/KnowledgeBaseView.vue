<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { api, getToken, getCurrentUserId } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';
import { kbsStore } from '@/stores/kbs';
import KbSidebar from '@/components/KbSidebar.vue';
import MemberPanel from '@/components/MemberPanel.vue';
import DocTable from '@/components/DocTable.vue';
import QuickStartGuide from '@/components/QuickStartGuide.vue';

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
// 资产快照（产品审视2 盲区 E 落地：留存钩子，让用户看到自己在平台上的积累）
const myAssets = ref(null);

async function loadMyAssets() {
  try {
    myAssets.value = await api('/api/me/summary');
  } catch (err) {
    myAssets.value = null;
  }
}

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
    loadMyAssets();
  }
);

onMounted(() => {
  loadKbs();
  loadTeams();
  loadMyAssets();
});
onBeforeUnmount(() => {
  if (pollTimer.value) clearInterval(pollTimer.value);
});
</script>

<template>
  <!-- 快速上手引导（产品 P1-2：业务人员 3 步闭环，首次展示、可关闭） -->
  <QuickStartGuide />
  <!-- 资产快照（留存钩子）：让用户看到自己在平台上的积累 -->
  <div v-if="myAssets" class="asset-strip">
    <span class="asset-label">我的资产</span>
    <span class="asset-item"><b>{{ myAssets.kbCount }}</b>知识库</span>
    <span class="asset-item"><b>{{ myAssets.docCount }}</b>文档</span>
    <span class="asset-item"><b>{{ myAssets.skillCount }}</b>技能</span>
    <span class="asset-item"><b>{{ myAssets.workflowCount }}</b>工作流</span>
    <span class="asset-item"><b>{{ myAssets.conversationCount }}</b>会话</span>
    <span class="asset-item"><b>{{ myAssets.askCount }}</b>提问</span>
  </div>
  <section class="kb-grid">
    <div class="kb-left">
      <KbSidebar :kbs="kbsStore.kbs" :current-kb-id="currentKbId" :teams="teams" @select="selectKb" @created="onKbCreated" />
      <MemberPanel :key="memberReloadKey" :kb-id="currentKbId" />
    </div>
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
  </section>
</template>

<style scoped>
/* 资产快照条（留存钩子） */
.asset-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 16px;
  margin-bottom: 12px;
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel-2, transparent);
  font-size: 13px;
}

.asset-label {
  color: var(--muted);
  font-weight: 600;
  margin-right: 4px;
}

.asset-item b {
  color: var(--accent);
  font-size: 16px;
  margin-right: 4px;
}

.kb-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 300px 1fr;
  align-items: start;
}

/* 左侧栏整列：知识库列表 + 成员，内容多时列内滚动 */
.kb-left {
  height: calc(100vh - 110px);
  overflow-y: auto;
  display: grid;
  align-content: start;
  gap: 16px;
}

/* 成员面板：跟随左侧栏，不额外撑高页面 */
.kb-left .member-panel {
  max-height: 160px;
  overflow-y: auto;
}

.kb-main {
  min-width: 0;
  height: calc(100vh - 110px);
  overflow-y: auto;
  align-content: start;
}

.tags-input {
  width: 200px;
}

@media (max-width: 900px) {
  .kb-grid {
    grid-template-columns: 1fr;
  }
  .kb-left, .kb-main {
    height: auto;
    max-height: 420px;
    overflow-y: auto;
  }
}
</style>
