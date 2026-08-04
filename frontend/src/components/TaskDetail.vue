<script setup>
import { ref, onMounted } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';

const props = defineProps({
  documentId: { type: Number, required: true }
});

const task = ref(null);
const error = ref('');

async function load() {
  try {
    task.value = await api(`/api/documents/${props.documentId}/task`);
  } catch (err) {
    error.value = err.message;
    showToast(err.message, true);
  }
}

onMounted(load);
</script>

<template>
  <div v-if="error" class="empty">{{ error }}</div>
  <div v-else-if="task" class="task-info">
    <p>任务 ID：{{ task.taskId }}</p>
    <p>文档 ID：{{ task.documentId }}</p>
    <p>状态：<span class="status" :class="task.status">{{ task.status }}</span></p>
    <p>重试：{{ task.retryCount }} / {{ task.maxRetries }}</p>
    <p v-if="task.errorMessage" class="risk-level HIGH">{{ task.errorMessage }}</p>
  </div>
  <div v-else class="empty">加载中…</div>
</template>

<style scoped>
.task-info p {
  margin: 6px 0;
}
</style>
