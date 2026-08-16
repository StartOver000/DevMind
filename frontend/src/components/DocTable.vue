<script setup>
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';
import { openModal } from '@/stores/modal';
import { session } from '@/stores/session';
import TaskDetail from '@/components/TaskDetail.vue';
import VersionsList from '@/components/VersionsList.vue';
import DocumentPreview from '@/components/DocumentPreview.vue';

const props = defineProps({
  docs: { type: Array, default: () => [] },
  kbId: { type: Number, default: null }
});
const emit = defineEmits(['refresh']);

// 文档状态中文化（业务人员视角审视：状态不应是英文技术值）
function docStatusLabel(s) {
  const map = {
    COMPLETED: '已完成', PROCESSING: '处理中', UPLOADED: '已上传',
    FAILED: '失败', DELETED: '已删除', PENDING: '等待中'
  };
  return map[s] || s;
}

async function uploadFile(file) {
  if (!props.kbId) {
    showToast('请先选择知识库', true);
    return;
  }
  const form = new FormData();
  form.append('file', file);
  try {
    const doc = await api(`/api/knowledge-bases/${props.kbId}/documents`, {
      method: 'POST',
      body: form
    });
    showToast(doc.duplicate ? '内容重复，已返回已有文档' : '上传成功，后台处理中');
    emit('refresh');
  } catch (err) {
    showToast(err.message, true);
  }
}

function pickFileForUpdate(documentId) {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.md,.markdown,.pdf';
  input.onchange = () => {
    if (input.files[0]) {
      updateDocument(documentId, input.files[0]);
    }
  };
  input.click();
}

async function updateDocument(documentId, file) {
  const form = new FormData();
  form.append('file', file);
  try {
    const doc = await api(`/api/documents/${documentId}/versions`, {
      method: 'POST',
      body: form
    });
    showToast(doc.duplicate ? '内容未变化' : '新版本已提交处理');
    emit('refresh');
  } catch (err) {
    showToast(err.message, true);
  }
}

async function deleteDoc(documentId) {
  if (!window.confirm('确认删除该文档？')) return;
  try {
    await api(`/api/documents/${documentId}`, { method: 'DELETE' });
    showToast('文档已删除');
    emit('refresh');
  } catch (err) {
    showToast(err.message, true);
  }
}

function showTask(documentId) {
  openModal(`任务 #${documentId}`, TaskDetail, { documentId });
}

function showVersions(documentId) {
  openModal(`文档版本历史（${documentId}）`, VersionsList, { documentId });
}

function showPreview(doc) {
  openModal(`预览：${doc.fileName}`, DocumentPreview, { documentId: doc.id });
}
</script>

<template>
  <div v-if="!docs.length" class="empty">还没有文档，点击“上传文档”添加</div>
  <div v-else class="table-wrap">
    <table>
      <thead>
        <tr>
          <th>文件名</th>
          <th>类型</th>
          <th>状态</th>
          <th>文本块</th>
          <th>上传时间</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="doc in docs" :key="doc.id">
          <td>{{ doc.fileName }}</td>
          <td>{{ doc.fileType }}</td>
          <td><span class="status" :class="doc.status">{{ docStatusLabel(doc.status) }}</span></td>
          <td>{{ doc.chunkCount }}</td>
          <td>{{ formatTime(doc.createdTime) }}</td>
          <td>
            <div class="actions">
              <button @click="showPreview(doc)">预览</button>
              <button @click="pickFileForUpdate(doc.id)">更新</button>
              <button @click="showVersions(doc.id)">版本</button>
              <button @click="showTask(doc.id)">任务</button>
              <button @click="deleteDoc(doc.id)">删除</button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
