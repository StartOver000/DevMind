<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';
import { kbsStore } from '@/stores/kbs';
import { openModal } from '@/stores/modal';
import DocumentPreview from '@/components/DocumentPreview.vue';

const chatKbIds = ref([]);
const chatTopK = ref(5);
const chatQuestion = ref('');
const chatTags = ref('');
const conversationId = ref(null);
const result = ref(null);
const loading = ref(false);
const conversations = ref([]);
const displayedAnswer = ref('');
let typeTimer = null;

function stopTyping() {
  if (typeTimer) {
    clearInterval(typeTimer);
    typeTimer = null;
  }
}

function typeAnswer(text) {
  stopTyping();
  const full = text || '';
  displayedAnswer.value = '';
  if (!full) {
    return;
  }
  let i = 0;
  typeTimer = setInterval(() => {
    i += 2;
    displayedAnswer.value = full.slice(0, i);
    if (i >= full.length) {
      displayedAnswer.value = full;
      stopTyping();
    }
  }, 16);
}

function parseTags(value) {
  if (!value) return undefined;
  const tags = value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
  return tags.length ? tags : undefined;
}

async function ensureKbs() {
  try {
    await kbsStore.load();
    // 自动选中第一个知识库
    if (!chatKbIds.value.length && kbsStore.kbs.length) {
      chatKbIds.value = [kbsStore.kbs[0].id];
    }
  } catch (err) {
    showToast(err.message, true);
  }
}

async function loadConversations() {
  try {
    const data = await api('/api/conversations?limit=50');
    conversations.value = data.items || [];
  } catch (err) {
    conversations.value = [];
  }
}

function newConversation() {
  conversationId.value = null;
  result.value = null;
  chatQuestion.value = '';
  stopTyping();
  displayedAnswer.value = '';
}

async function selectConversation(id) {
  try {
    const data = await api(`/api/conversations/${id}/messages`);
    conversationId.value = id;
    const messages = data.messages || [];
    const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
    stopTyping();
    displayedAnswer.value = lastAssistant ? lastAssistant.content : '';
    result.value = lastAssistant
      ? { conversationId: id, answer: lastAssistant.content, references: [] }
      : { conversationId: id, answer: '（该会话暂无助手回复）', references: [] };
  } catch (err) {
    showToast(err.message, true);
  }
}

function previewReference(ref) {
  openModal(`来源：${ref.documentName}`, DocumentPreview, { documentId: ref.documentId });
}

async function removeConversation(id) {
  if (!confirm('确认删除该会话？')) return;
  try {
    await api(`/api/conversations/${id}`, { method: 'DELETE' });
    if (conversationId.value === id) {
      conversationId.value = null;
      result.value = null;
    }
    await loadConversations();
    showToast('会话已删除');
  } catch (err) {
    showToast(err.message, true);
  }
}

async function sendChat() {
  const kbIds = chatKbIds.value.map(Number).filter(Boolean);
  const question = chatQuestion.value.trim();
  const topK = Number(chatTopK.value || 5);
  if (!kbIds.length || !question) {
    showToast('请选择知识库并输入问题', true);
    return;
  }
  loading.value = true;
  result.value = null;
  try {
    let data;
    if (kbIds.length === 1) {
      data = await api(`/api/knowledge-bases/${kbIds[0]}/chat`, {
        method: 'POST',
        body: JSON.stringify({ question, topK, conversationId: conversationId.value, tags: parseTags(chatTags.value) })
      });
      conversationId.value = data.conversationId;
    } else {
      conversationId.value = null;
      data = await api('/api/chat/aggregate', {
        method: 'POST',
        body: JSON.stringify({ knowledgeBaseIds: kbIds, question, topK, tags: parseTags(chatTags.value) })
      });
    }
    result.value = data;
    typeAnswer(data.answer);
    await loadConversations();
  } catch (err) {
    result.value = { error: err.message };
    showToast(err.message, true);
  } finally {
    loading.value = false;
  }
}

watch(
  () => session.reloadKey,
  () => {
    chatKbIds.value = [];
    conversationId.value = null;
    ensureKbs();
    loadConversations();
  }
);

onMounted(() => {
  ensureKbs();
  loadConversations();
});

onBeforeUnmount(() => {
  stopTyping();
});
</script>

<template>
  <section class="chat-grid">
    <div class="chat-left">
      <div class="panel chat-form">
      <h2>RAG 问答</h2>
      <label>知识库（可多选，多选为跨库聚合问答）
        <div class="kb-check-group">
          <label v-for="kb in kbsStore.kbs" :key="kb.id" class="kb-check">
            <input type="checkbox" :value="kb.id" v-model="chatKbIds">
            {{ kb.name }}
          </label>
        </div>
      </label>
      <label>Top-K
        <input v-model.number="chatTopK" type="number" min="1" max="10">
      </label>
      <label>标签过滤（逗号分隔，可选）
        <input v-model="chatTags" placeholder="例如：mysql,索引">
      </label>
      <textarea
        v-model="chatQuestion"
        rows="4"
        maxlength="2000"
        placeholder="输入问题，例如：MySQL 深分页为什么会变慢？"
      ></textarea>
      <button class="primary" :disabled="loading" @click="sendChat">
        {{ loading ? '检索中…' : '提问' }}
      </button>
    </div>
    <div class="panel chat-history">
      <div class="panel-header">
        <h2>历史会话</h2>
        <button class="secondary small" @click="newConversation">新建</button>
      </div>
      <div v-if="!conversations.length" class="empty small">暂无历史会话</div>
      <div
        v-for="c in conversations"
        :key="c.id"
        class="conv-item"
        :class="{ active: c.id === conversationId }"
      >
        <button class="conv-main" @click="selectConversation(c.id)">
          <span class="conv-title">{{ c.title }}</span>
          <span class="conv-time">{{ formatTime(c.updatedTime) }}</span>
        </button>
        <button class="conv-del" title="删除会话" @click="removeConversation(c.id)">✕</button>
      </div>
    </div>
    </div>
    <div class="panel chat-result" :class="{ empty: !result }">
      <div v-if="!result" class="empty">回答会显示在这里</div>
      <template v-else-if="result.error">
        <div class="empty">{{ result.error }}</div>
      </template>
      <template v-else>
        <div class="panel-header">
          <h2>回答</h2>
          <span class="status SUCCEEDED">会话 #{{ result.conversationId }}</span>
        </div>
        <div class="answer">{{ displayedAnswer }}</div>
        <h3>引用来源（{{ result.references.length }}）</h3>
        <div v-for="(ref, i) in result.references" :key="i" class="reference">
          <div class="head">
            <span>{{ i + 1 }}. {{ ref.documentName }}</span>
            <span class="ref-actions">
              <span class="score">{{ ref.similarityScore.toFixed(4) }}</span>
              <button v-if="ref.documentId" class="small" @click="previewReference(ref)">预览</button>
            </span>
          </div>
          <p>{{ ref.content }}</p>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.chat-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 380px 1fr;
  align-items: start;
}

.chat-left {
  display: grid;
  gap: 16px;
  align-content: start;
}

.chat-form {
  display: grid;
  gap: 12px;
}

.kb-check-group {
  display: grid;
  gap: 4px;
}

.kb-check {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
}

.kb-check input {
  width: auto;
}

.chat-history {
  display: grid;
  gap: 8px;
}

.conv-item {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 6px 8px;
}

.conv-item.active {
  border-color: var(--accent);
  background: var(--accent-weak);
}

.conv-main {
  flex: 1;
  display: grid;
  gap: 2px;
  text-align: left;
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px;
}

.conv-title {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  font-size: 11px;
  color: var(--muted);
}

.conv-del {
  background: none;
  border: none;
  color: var(--muted);
  cursor: pointer;
  padding: 2px 4px;
}

.conv-del:hover {
  color: var(--danger, #d33);
}

.ref-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.ref-actions .small {
  padding: 2px 8px;
  font-size: 12px;
}

.chat-result h3 {
  margin: 16px 0 4px;
  font-size: 14px;
}

@media (max-width: 900px) {
  .chat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
