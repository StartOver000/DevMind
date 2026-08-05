<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import { api, formatTime } from '@/api/client';
import { renderMarkdown } from '@/utils/markdown';
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
const lastQuestion = ref('');
const messagesEl = ref(null);
let typeTimer = null;

// Agent 模式
const mode = ref('rag');
const agentResult = ref(null);
const agentConversations = ref([]);
const agentLoading = ref(false);

const currentResult = computed(() => (mode.value === 'rag' ? result.value : agentResult.value));
const currentError = computed(() => currentResult.value?.error || '');
const hasAnswer = computed(() => !!currentResult.value && !currentResult.value.error);
const currentReferences = computed(() => {
  const r = currentResult.value;
  return (r && r.references) ? r.references : [];
});

function scrollToBottom() {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
    }
  });
}

// 打字过程中持续滚动到底部
watch(displayedAnswer, scrollToBottom);
watch([loading, agentLoading], () => {
  if (loading.value || agentLoading.value) scrollToBottom();
});

function switchMode(next) {
  if (next === mode.value) return;
  mode.value = next;
  stopTyping();
  displayedAnswer.value = '';
  lastQuestion.value = '';
  if (next === 'agent') {
    result.value = null;
    loadAgentConversations();
  } else {
    agentResult.value = null;
    loadConversations();
  }
}

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

async function loadAgentConversations() {
  try {
    const data = await api('/api/agent/conversations?limit=50');
    agentConversations.value = Array.isArray(data) ? data : [];
  } catch (err) {
    agentConversations.value = [];
  }
}

function newConversation() {
  conversationId.value = null;
  result.value = null;
  agentResult.value = null;
  chatQuestion.value = '';
  lastQuestion.value = '';
  stopTyping();
  displayedAnswer.value = '';
}

async function selectConversation(id) {
  try {
    const data = await api(`/api/conversations/${id}/messages`);
    conversationId.value = id;
    // 同步该会话的知识库到选中状态，避免“会话不属于该知识库”
    const conv = conversations.value.find((c) => c.id === id);
    if (conv && conv.knowledgeBaseId) {
      chatKbIds.value = [conv.knowledgeBaseId];
    }
    const messages = data.messages || [];
    const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
    const lastUserMsg = [...messages].reverse().find((m) => m.role === 'user');
    stopTyping();
    displayedAnswer.value = lastAssistant ? lastAssistant.content : '';
    lastQuestion.value = lastUserMsg ? lastUserMsg.content : '';
    result.value = lastAssistant
      ? { conversationId: id, answer: lastAssistant.content, references: [] }
      : { conversationId: id, answer: '（该会话暂无助手回复）', references: [] };
    scrollToBottom();
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
  lastQuestion.value = question;
  chatQuestion.value = '';
  scrollToBottom();
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

async function sendAgent() {
  const question = chatQuestion.value.trim();
  if (!question) {
    showToast('请输入问题', true);
    return;
  }
  agentLoading.value = true;
  agentResult.value = null;
  lastQuestion.value = question;
  chatQuestion.value = '';
  scrollToBottom();
  try {
    const data = await api('/api/agent/chat', {
      method: 'POST',
      body: JSON.stringify({ conversationId: conversationId.value || 0, question })
    });
    conversationId.value = data.conversationId;
    agentResult.value = data;
    typeAnswer(data.answer);
    await loadAgentConversations();
  } catch (err) {
    agentResult.value = { error: err.message };
    showToast(err.message, true);
  } finally {
    agentLoading.value = false;
  }
}

async function selectAgentConversation(id) {
  const conv = agentConversations.value.find((c) => c.id === id);
  conversationId.value = id;
  stopTyping();
  displayedAnswer.value = '';
  agentResult.value = null;
  try {
    // 加载会话历史消息（记忆）
    const data = await api(`/api/agent/conversations/${id}/messages`);
    const messages = data && Array.isArray(data) ? data : [];
    const lastUser = [...messages].reverse().find((m) => m.role === 'user');
    const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
    lastQuestion.value = lastUser ? lastUser.content : (conv ? conv.title : '');
    if (lastAssistant) {
      displayedAnswer.value = lastAssistant.content;
      agentResult.value = {
        conversationId: id,
        answer: lastAssistant.content,
        references: [],
        toolTrace: []
      };
    } else if (conv) {
      chatQuestion.value = conv.title;
      lastQuestion.value = conv.title;
    }
    scrollToBottom();
  } catch (err) {
    if (conv) {
      chatQuestion.value = conv.title;
      lastQuestion.value = conv.title;
    }
    showToast(err.message, true);
  }
}

async function removeAgentConversation(id) {
  if (!confirm('确认删除该会话？')) return;
  try {
    await api(`/api/agent/conversations/${id}`, { method: 'DELETE' });
    if (conversationId.value === id) {
      conversationId.value = null;
      agentResult.value = null;
    }
    await loadAgentConversations();
    showToast('会话已删除');
  } catch (err) {
    showToast(err.message, true);
  }
}

watch(
  () => session.reloadKey,
  () => {
    chatKbIds.value = [];
    conversationId.value = null;
    ensureKbs();
    loadConversations();
    loadAgentConversations();
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
  <section class="chat-layout">
    <!-- 左侧历史会话 -->
    <aside class="chat-sidebar">
      <div class="sidebar-head">
        <h2>历史会话</h2>
        <button class="secondary small" @click="newConversation">新建</button>
      </div>
      <div class="mode-switch">
        <button class="small" :class="{ active: mode === 'rag' }" @click="switchMode('rag')">RAG</button>
        <button class="small" :class="{ active: mode === 'agent' }" @click="switchMode('agent')">Agent</button>
      </div>
      <div class="conv-list">
        <template v-if="mode === 'rag'">
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
        </template>
        <template v-else>
          <div v-if="!agentConversations.length" class="empty small">暂无 Agent 会话</div>
          <div
            v-for="c in agentConversations"
            :key="c.id"
            class="conv-item"
            :class="{ active: c.id === conversationId }"
          >
            <button class="conv-main" @click="selectAgentConversation(c.id)">
              <span class="conv-title">{{ c.title }}</span>
              <span class="conv-time">{{ formatTime(c.createdTime) }}</span>
            </button>
            <button class="conv-del" title="删除会话" @click="removeAgentConversation(c.id)">✕</button>
          </div>
        </template>
      </div>
    </aside>

    <!-- 右侧对话区 -->
    <div class="chat-main">
      <div class="chat-messages" ref="messagesEl">
        <div v-if="!lastQuestion && !currentResult" class="empty">
          {{ mode === 'rag'
            ? '选择左侧会话或输入问题开始问答'
            : '选择左侧会话或输入问题，Agent 会自动调用工具（知识库检索/SQL 诊断等）回答' }}
        </div>

        <!-- 用户消息 -->
        <div v-if="lastQuestion" class="msg user">
          <div class="bubble">{{ lastQuestion }}</div>
        </div>

        <!-- 回答消息 -->
        <div v-if="hasAnswer" class="msg assistant">
          <div class="bubble">
            <div
              v-if="mode === 'agent' && agentResult && agentResult.toolTrace && agentResult.toolTrace.length"
              class="tool-trace"
            >
              <details>
                <summary>Agent 执行轨迹（{{ agentResult.toolTrace.length }} 步）</summary>
                <div v-for="(t, i) in agentResult.toolTrace" :key="i" class="tool-trace-item" :class="{ fail: !t.ok }">
                  <span class="tt-icon">{{ t.ok ? '✓' : '✗' }}</span>
                  <span class="tt-name">{{ t.tool }}</span>
                  <span class="tt-args">{{ t.args }}</span>
                  <span class="tt-time">{{ t.costMs }}ms</span>
                </div>
              </details>
            </div>
            <div class="markdown-body" v-html="renderMarkdown(displayedAnswer)"></div>
            <div v-if="currentReferences.length" class="refs">
              <h4>引用来源（{{ currentReferences.length }}）</h4>
              <div v-for="(ref, i) in currentReferences" :key="i" class="reference">
                <div class="head">
                  <span>{{ i + 1 }}. {{ ref.documentName }}</span>
                  <span class="ref-actions">
                    <span class="score">{{ ref.similarityScore.toFixed(4) }}</span>
                    <button v-if="ref.documentId" class="small" @click="previewReference(ref)">预览</button>
                  </span>
                </div>
                <p>{{ ref.content }}</p>
              </div>
            </div>
          </div>
        </div>

        <!-- 错误消息 -->
        <div v-if="currentError" class="msg assistant">
          <div class="bubble error">{{ currentError }}</div>
        </div>

        <!-- 思考中 -->
        <div v-if="loading || agentLoading" class="msg assistant">
          <div class="bubble thinking">
            <span class="dot"></span><span class="dot"></span><span class="dot"></span>
            {{ mode === 'agent' ? 'Agent 思考中…' : '检索中…' }}
          </div>
        </div>
      </div>

      <!-- 底部输入区 -->
      <div class="chat-input">
        <details v-if="mode === 'rag'" class="rag-params">
          <summary>检索参数</summary>
          <div class="rag-params-body">
            <div class="kb-line">
              <span class="kb-label">知识库：</span>
              <div class="kb-check-group">
                <label v-for="kb in kbsStore.kbs" :key="kb.id" class="kb-check">
                  <input type="checkbox" :value="kb.id" v-model="chatKbIds">
                  {{ kb.name }}
                </label>
              </div>
            </div>
            <label class="param">Top-K
              <input v-model.number="chatTopK" type="number" min="1" max="10" class="narrow">
            </label>
            <label class="param">标签过滤
              <input v-model="chatTags" placeholder="例如：mysql,索引" class="wide">
            </label>
          </div>
        </details>
        <div class="input-row">
          <textarea
            v-model="chatQuestion"
            rows="3"
            maxlength="2000"
            :placeholder="mode === 'rag'
              ? '输入问题，例如：MySQL 深分页为什么会变慢？'
              : '输入问题，Agent 会自动调用工具（知识库检索/SQL 诊断等）回答'"
            @keydown.enter.exact.prevent="mode === 'rag' ? sendChat() : sendAgent()"
          ></textarea>
          <button class="primary send-btn" :disabled="loading || agentLoading" @click="mode === 'rag' ? sendChat() : sendAgent()">
            {{ (mode === 'rag' ? loading : agentLoading) ? '处理中…' : '发送' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.chat-layout {
  display: flex;
  gap: 16px;
  height: calc(100vh - 130px);
  min-height: 480px;
}

/* ---- 左侧历史会话侧边栏 ---- */
.chat-sidebar {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  padding: 12px;
  overflow: hidden;
}

.sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sidebar-head h2 {
  margin: 0;
  font-size: 15px;
}

.mode-switch {
  display: flex;
  gap: 4px;
}

.mode-switch button {
  flex: 1;
  padding: 6px 0;
  color: var(--muted);
}

.mode-switch button.active {
  background: var(--accent-weak);
  color: var(--accent);
  font-weight: 600;
}

.conv-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 0;
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
  min-width: 0;
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
  color: var(--danger);
}

/* ---- 右侧对话区 ---- */
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  overflow: hidden;
}

.chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.msg {
  display: flex;
}

.msg.user {
  justify-content: flex-end;
}

.msg.assistant {
  justify-content: flex-start;
}

.bubble {
  max-width: 82%;
  padding: 10px 14px;
  border-radius: 12px;
  line-height: 1.6;
  font-size: 14px;
  word-break: break-word;
}

.msg.user .bubble {
  background: var(--accent);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.msg.assistant .bubble {
  background: var(--alt-bg);
  border: 1px solid var(--line);
  border-bottom-left-radius: 4px;
}

.bubble.error {
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 40%, transparent);
}

.bubble.thinking {
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: 8px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--muted);
  display: inline-block;
  animation: blink 1.2s infinite;
}

.dot:nth-child(2) {
  animation-delay: 0.2s;
}

.dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 80%, 100% { opacity: 0.3; }
  40% { opacity: 1; }
}

/* ---- 底部输入区 ---- */
.chat-input {
  border-top: 1px solid var(--line);
  padding: 12px 16px 16px;
  background: var(--panel);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rag-params {
  font-size: 13px;
}

.rag-params summary {
  cursor: pointer;
  color: var(--accent);
  font-weight: 600;
  user-select: none;
}

.rag-params-body {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  padding: 10px;
  margin-top: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--alt-bg);
}

.kb-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.kb-label {
  color: var(--muted);
  padding-top: 2px;
  flex-shrink: 0;
}

.kb-check-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.kb-check {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}

.kb-check input {
  width: auto;
}

.param {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 13px;
}

.param .narrow {
  width: 70px;
}

.param .wide {
  width: 180px;
}

.input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.input-row textarea {
  flex: 1;
  resize: none;
}

.send-btn {
  white-space: nowrap;
  padding: 10px 22px;
}

/* ---- Agent 轨迹 ---- */
.tool-trace {
  margin-bottom: 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 10px;
  background: var(--alt-bg);
}

.tool-trace summary {
  cursor: pointer;
  color: var(--accent);
  font-size: 13px;
  font-weight: 600;
}

.tool-trace-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 2px;
  font-size: 12px;
  border-bottom: 1px solid var(--line);
}

.tool-trace-item:last-child {
  border-bottom: 0;
}

.tt-icon {
  width: 16px;
  color: var(--ok);
}

.tool-trace-item.fail .tt-icon {
  color: var(--danger);
}

.tool-trace-item.fail .tt-name {
  color: var(--danger);
}

.tt-name {
  font-weight: 600;
  color: var(--text);
}

.tt-args {
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 45%;
}

.tt-time {
  margin-left: auto;
  color: var(--muted);
  flex-shrink: 0;
}

/* ---- 引用 ---- */
.refs {
  margin-top: 12px;
}

.refs h4 {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--muted);
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

@media (max-width: 900px) {
  .chat-layout {
    flex-direction: column;
    height: auto;
  }

  .chat-sidebar {
    width: 100%;
    max-height: 240px;
  }

  .chat-main {
    min-height: 480px;
  }
}
</style>
