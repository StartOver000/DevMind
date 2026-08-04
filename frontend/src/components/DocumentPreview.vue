<script setup>
import { ref, onMounted } from 'vue';
import { apiText } from '@/api/client';

const props = defineProps({
  documentId: { type: Number, required: true }
});

const content = ref('加载中…');

onMounted(async () => {
  try {
    content.value = await apiText(`/api/documents/${props.documentId}/content`);
  } catch (err) {
    content.value = '加载失败：' + err.message;
  }
});
</script>

<template>
  <pre class="preview">{{ content }}</pre>
</template>

<style scoped>
.preview {
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 60vh;
  overflow: auto;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 12px;
  font-size: 13px;
  line-height: 1.6;
}
</style>
