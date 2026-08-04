<script setup>
import { ref, computed, onMounted } from 'vue';
import { apiText } from '@/api/client';
import { renderMarkdown } from '@/utils/markdown';

const props = defineProps({
  documentId: { type: Number, required: true }
});

const content = ref('加载中…');

const html = computed(() => renderMarkdown(content.value));

onMounted(async () => {
  try {
    content.value = await apiText(`/api/documents/${props.documentId}/content`);
  } catch (err) {
    content.value = '加载失败：' + err.message;
  }
});
</script>

<template>
  <div class="preview markdown-body" v-html="html"></div>
</template>

<style scoped>
.preview {
  max-height: 60vh;
  overflow: auto;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 16px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text);
}

/* Markdown 渲染样式 */
.preview :deep(h1), .preview :deep(h2), .preview :deep(h3),
.preview :deep(h4), .preview :deep(h5), .preview :deep(h6) {
  margin: 1.2em 0 0.5em;
  line-height: 1.3;
  font-weight: 600;
}
.preview :deep(h1) { font-size: 1.5em; border-bottom: 1px solid var(--line); padding-bottom: 0.3em; }
.preview :deep(h2) { font-size: 1.3em; border-bottom: 1px solid var(--line); padding-bottom: 0.3em; }
.preview :deep(h3) { font-size: 1.15em; }
.preview :deep(p) { margin: 0.6em 0; }
.preview :deep(ul), .preview :deep(ol) { margin: 0.6em 0; padding-left: 1.6em; }
.preview :deep(li) { margin: 0.2em 0; }
.preview :deep(blockquote) {
  margin: 0.8em 0;
  padding: 0.2em 1em;
  border-left: 3px solid var(--primary, #4f8cff);
  background: color-mix(in srgb, var(--bg-soft, #f5f5f5) 60%, transparent);
  color: var(--text-soft);
}
.preview :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 0.9em;
  background: color-mix(in srgb, var(--text) 8%, transparent);
  border-radius: 4px;
  padding: 0.15em 0.4em;
}
.preview :deep(pre) {
  background: color-mix(in srgb, var(--text) 6%, transparent);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 12px;
  overflow: auto;
}
.preview :deep(pre code) { background: none; padding: 0; }
.preview :deep(a) { color: var(--primary, #4f8cff); }
.preview :deep(table) {
  border-collapse: collapse;
  margin: 0.8em 0;
  width: 100%;
}
.preview :deep(th), .preview :deep(td) {
  border: 1px solid var(--line);
  padding: 6px 10px;
  text-align: left;
}
.preview :deep(th) { background: color-mix(in srgb, var(--text) 6%, transparent); }
.preview :deep(hr) { border: none; border-top: 1px solid var(--line); margin: 1em 0; }
.preview :deep(img) { max-width: 100%; }
</style>
