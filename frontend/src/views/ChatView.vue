<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import { api, formatTime, getCurrentUserId, getToken } from '@/api/client';
import { renderMarkdown } from '@/utils/markdown';
import { streamFetch } from '@/utils/sse';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';
import { kbsStore } from '@/stores/kbs';
import { openModal } from '@/stores/modal';
import DocumentPreview from '@/components/DocumentPreview.vue';

/** 附加身份 headers（与 api client 一致） */
function userHeaders() {
  const headers = { 'X-User-Id': String(getCurrentUserId()) };
  const token = getToken();
  if (token) headers['Authorization'] = 'Bearer ' + token;
  return headers;
}

const chatKbIds = ref([]);
const chatTopK = ref(5);
const chatQuestion = ref('');
const chatTags = ref('');
const conversationId = ref(null);
const result = ref(null);
const loading = ref(false);
const conversations = ref([]);
const displayedAnswer = ref('');
const messagesEl = ref(null);
let typeTimer = null;

// 消息流（{ role, content, time, error?, trace? }），支持完整多轮历史
const thread = ref([]);
// 正在打字的消息下标（渲染用 displayedAnswer）
const typingIndex = ref(-1);

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

// 长期记忆
const memoryText = ref('');

async function loadMemory() {
  try {
    const data = await api('/api/agent/memory');
    memoryText.value = Array.isArray(data)
      ? data.map((m) => `${m.key}: ${m.value}`).join('\n')
      : '';
  } catch (err) {
    memoryText.value = '';
  }
}

async function saveMemory() {
  try {
    const items = memoryText.value.split('\n')
      .map((l) => l.trim())
      .filter(Boolean)
      .map((l) => {
        const idx = l.indexOf(':');
        if (idx === -1) return null;
        return { key: l.slice(0, idx).trim(), value: l.slice(idx + 1).trim() };
      })
      .filter(Boolean);
    await api('/api/agent/memory', { method: 'PUT', body: JSON.stringify({ items }) });
    showToast('长期记忆已保存');
  } catch (err) {
    showToast(err.message, true);
  }
}

function nowText() {
  // 本地 24 小时制（与 formatTime 输出一致，不再暴露 ISO 时间戳）
  return formatTime(new Date());
}

function pushThread(role, content, time, extra) {
  thread.value.push({ role, content, time, ...(extra || {}) });
  scrollToBottom();
}

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
  typingIndex.value = -1;
  thread.value = [];
  if (next === 'agent') {
    result.value = null;
    loadAgentConversations();
    loadMemory();
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
  typingIndex.value = thread.value.length - 1;
  if (!full) {
    typingIndex.value = -1;
    return;
  }
  let i = 0;
  typeTimer = setInterval(() => {
    i += 2;
    displayedAnswer.value = full.slice(0, i);
    if (i >= full.length) {
      displayedAnswer.value = full;
      typingIndex.value = -1;
      stopTyping();
    }
  }, 16);
}

function parseTags(value) {
  if (!value) return undefined;
  const tags = value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
  return tags.length ? tags : undefined;
}

/** 解析 plan trace 为计划展示结构（goal + 步骤列表）；非 plan trace 返回 null */
function parsePlanSteps(t) {
  if (!t || t.tool !== 'plan') return null;
  try {
    const data = JSON.parse(t.args || '{}');
    const steps = Array.isArray(data.steps) ? data.steps : [];
    return { goal: data.goal || '', steps };
  } catch (e) {
    return null;
  }
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
  stopTyping();
  displayedAnswer.value = '';
  typingIndex.value = -1;
  thread.value = [];
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
    thread.value = messages.map((m) => ({
      role: m.role === 'user' ? 'user' : 'assistant',
      content: m.content,
      time: formatTime(m.createdTime)
    }));
    const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
    stopTyping();
    displayedAnswer.value = '';
    typingIndex.value = -1;
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
  chatQuestion.value = '';
  pushThread('user', question, nowText());
  // 占位 assistant 消息，流式增量填充
  const assistantIndex = thread.value.length;
  pushThread('assistant', '', nowText());
  try {
    if (kbIds.length === 1) {
      await streamFetch(
        `/api/knowledge-bases/${kbIds[0]}/chat/stream`,
        { question, topK, conversationId: conversationId.value, tags: parseTags(chatTags.value) },
        {
          onMeta: (meta) => {
            if (meta.conversationId) conversationId.value = meta.conversationId;
            const refs = Array.isArray(meta.references) ? meta.references : [];
            result.value = { conversationId: conversationId.value, answer: '', references: refs };
            if (thread.value[assistantIndex]) thread.value[assistantIndex].refs = refs;
            scrollToBottom();
          },
          onDelta: (chunk) => {
            const m = thread.value[assistantIndex];
            if (m) {
              m.reconnecting = false;
              m.content += chunk;
              scrollToBottom();
            }
          },
          onError: (err) => { throw err; }
        },
        {
          headers: userHeaders(),
          retries: 2,
          retryDelay: 1500,
          onRetry: (attempt, total) => {
            const m = thread.value[assistantIndex];
            if (m) { m.content = ''; m.reconnecting = true; }
            showToast(`连接中断，正在重连（${attempt}/${total}）…`);
          }
        }
      );
      // 流结束后补全 result（引用已在 meta 中设置）
      const finalMsg = thread.value[assistantIndex];
      if (result.value && finalMsg) result.value.answer = finalMsg.content;
    } else {
      // 多库聚合：无流式端点，走原一次性接口
      conversationId.value = null;
      const data = await api('/api/chat/aggregate', {
        method: 'POST',
        body: JSON.stringify({ knowledgeBaseIds: kbIds, question, topK, tags: parseTags(chatTags.value) })
      });
      result.value = data;
      if (thread.value[assistantIndex]) {
        thread.value[assistantIndex].content = data.answer;
        thread.value[assistantIndex].refs = data.references || [];
      }
      scrollToBottom();
    }
    await loadConversations();
  } catch (err) {
    const m = thread.value[assistantIndex];
    if (m) {
      m.content = err.message;
      m.error = true;
    }
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
  chatQuestion.value = '';
  pushThread('user', question, nowText());
  // 占位 assistant 消息，工具轨迹实时挂载、回答增量填充
  const assistantIndex = thread.value.length;
  pushThread('assistant', '', nowText());
  const traces = [];
  try {
    await streamFetch(
      '/api/agent/chat/stream',
      { conversationId: conversationId.value || 0, question },
      {
        onTrace: (t) => {
          traces.push(t);
          const m = thread.value[assistantIndex];
          if (m) {
            m.trace = [...traces];
            scrollToBottom();
          }
        },
        onDelta: (chunk) => {
          const m = thread.value[assistantIndex];
          if (m) {
            m.reconnecting = false;
            m.content += chunk;
            scrollToBottom();
          }
        },
        onDone: (done) => {
          if (done && done.conversationId) conversationId.value = done.conversationId;
          const finalMsg = thread.value[assistantIndex];
          const answer = finalMsg ? finalMsg.content : '';
          agentResult.value = { conversationId: conversationId.value, answer, references: [], toolTrace: traces };
        },
        onError: (err) => { throw err; }
      },
      {
        headers: userHeaders(),
        retries: 2,
        retryDelay: 1500,
        onRetry: (attempt, total) => {
          // 重连会重跑 Agent：清空已收内容与轨迹，避免重复展示
          traces.length = 0;
          const m = thread.value[assistantIndex];
          if (m) { m.content = ''; m.trace = []; m.reconnecting = true; }
          showToast(`连接中断，正在重连（${attempt}/${total}）…`);
        }
      }
    );
    await loadAgentConversations();
  } catch (err) {
    // 移除空占位，展示错误
    thread.value = thread.value.slice(0, -1);
    agentResult.value = { error: err.message };
    pushThread('assistant', err.message, nowText(), { error: true });
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
    // 加载会话历史消息 + 工具轨迹（记忆）
    const [msgData, traceData] = await Promise.all([
      api(`/api/agent/conversations/${id}/messages`),
      api(`/api/agent/conversations/${id}/trace`)
    ]);
    const messages = msgData && Array.isArray(msgData) ? msgData : [];
    const trace = (traceData && Array.isArray(traceData)) ? traceData : [];
    thread.value = messages.map((m) => ({
      role: m.role === 'user' ? 'user' : 'assistant',
      content: m.content,
      time: formatTime(m.createdTime)
    }));
    if (!thread.value.length && conv) {
      thread.value = [{ role: 'user', content: conv.title, time: formatTime(conv.createdTime) }];
    }
    // 轨迹附到最后一条 assistant 消息
    if (trace.length && thread.value.length) {
      const last = thread.value[thread.value.length - 1];
      if (last.role === 'assistant') {
        last.trace = trace;
      }
    }
    const lastAssistant = [...thread.value].reverse().find((m) => m.role === 'assistant');
    stopTyping();
    displayedAnswer.value = '';
    typingIndex.value = -1;
    agentResult.value = lastAssistant
      ? { conversationId: id, answer: lastAssistant.content, references: [], toolTrace: trace }
      : { conversationId: id, answer: '', references: [], toolTrace: trace };
    scrollToBottom();
  } catch (err) {
    if (conv) {
      thread.value = [{ role: 'user', content: conv.title, time: formatTime(conv.createdTime) }];
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
        <div v-if="!thread.length && !loading && !agentLoading" class="empty">
          {{ mode === 'rag'
            ? '选择左侧会话或输入问题开始问答'
            : '选择左侧会话或输入问题，Agent 会自动调用工具（知识库检索/SQL 诊断等）回答' }}
        </div>

        <!-- 消息流（含时间戳，支持完整多轮历史） -->
        <div v-for="(m, i) in thread" :key="i" class="msg" :class="m.role">
          <div class="bubble" :class="{ error: m.error }">
            <div v-if="m.trace && m.trace.length" class="tool-trace">
              <details>
                <summary>Agent 执行轨迹（{{ m.trace.length }} 步）</summary>
                <div v-for="(t, j) in m.trace" :key="j" class="tool-trace-item" :class="{ fail: !t.ok, plan: t.tool === 'plan' }">
                  <template v-if="t.tool === 'plan'">
                    <span class="tt-icon">📋</span>
                    <span class="tt-name">计划</span>
                    <div class="plan-steps">
                      <div v-for="(s, si) in (parsePlanSteps(t)?.steps || [])" :key="si" class="plan-step">
                        <span class="ps-no">{{ si + 1 }}</span>
                        <span class="ps-tool">{{ s.tool }}</span>
                        <span class="ps-goal">{{ s.goal }}</span>
                      </div>
                    </div>
                  </template>
                  <template v-else>
                    <span class="tt-icon">{{ t.ok ? '✓' : '✗' }}</span>
                    <span class="tt-name">{{ t.tool }}</span>
                    <span class="tt-args">{{ t.args }}</span>
                    <span class="tt-time">{{ t.costMs }}ms</span>
                  </template>
                </div>
              </details>
            </div>
            <div v-if="m.reconnecting" class="reconnecting">连接已中断，正在重连…</div>
            <template v-if="m.role === 'assistant'">
              <div v-if="i === typingIndex" class="markdown-body" v-html="renderMarkdown(displayedAnswer)"></div>
              <div v-else class="markdown-body" v-html="renderMarkdown(m.content)"></div>
              <div v-if="i === thread.length - 1 && currentReferences.length" class="refs">
                <h4>引用来源（{{ currentReferences.length }}）</h4>
                <div v-for="(ref, k) in currentReferences" :key="k" class="reference">
                  <div class="head">
                    <span>{{ k + 1 }}. {{ ref.documentName }}</span>
                    <span class="ref-actions">
                      <span class="score">{{ ref.similarityScore.toFixed(4) }}</span>
                      <button v-if="ref.documentId" class="small" @click="previewReference(ref)">预览</button>
                    </span>
                  </div>
                  <p>{{ ref.content }}</p>
                </div>
              </div>
            </template>
            <template v-else>
              <div class="user-text">{{ m.content }}</div>
            </template>
            <div v-if="m.time" class="msg-time">{{ m.time }}</div>
          </div>
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
        <details v-if="mode === 'agent'" class="rag-params">
          <summary>长期记忆</summary>
          <div class="rag-params-body column">
            <textarea
              v-model="memoryText"
              rows="3"
              placeholder="每行一条，格式：偏好: 内容&#10;例如：&#10;语言: 中文&#10;回答风格: 简洁直接"
            ></textarea>
            <div class="memory-actions">
              <span class="memory-hint">会话结束后自动提取你的偏好，可手动编辑补充</span>
              <button class="secondary small" @click="saveMemory">保存记忆</button>
            </div>
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

.user-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-time {
  font-size: 11px;
  color: var(--muted);
  margin-top: 6px;
  text-align: right;
}

.msg.user .msg-time {
  color: rgba(255, 255, 255, 0.75);
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

.rag-params-body.column {
  flex-direction: column;
  align-items: stretch;
}

.memory-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.memory-hint {
  color: var(--muted);
  font-size: 12px;
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

/* Plan-Execute 计划卡片 */
.tool-trace-item.plan {
  align-items: flex-start;
  flex-direction: column;
  gap: 4px;
}

.tool-trace-item.plan .tt-icon {
  color: var(--accent);
}

.plan-steps {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding-left: 24px;
}

.plan-step {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.ps-no {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.ps-tool {
  font-weight: 600;
  color: var(--text);
  font-family: var(--mono, monospace);
}

.ps-goal {
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* SSE 断线重连提示 */
.reconnecting {
  margin-top: 6px;
  color: var(--warning, #b8860b);
  font-size: 12px;
  animation: blink 1.2s infinite;
}

@keyframes blink {
  50% { opacity: 0.4; }
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
