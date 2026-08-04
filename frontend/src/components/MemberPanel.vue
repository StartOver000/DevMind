<script setup>
import { ref, watch } from 'vue';
import { api } from '@/api/client';
import { showToast } from '@/stores/toast';

const props = defineProps({
  kbId: { type: Number, default: null }
});

const members = ref([]);
const memberUserId = ref('');
const memberRole = ref('MEMBER');
const loading = ref(false);

async function loadMembers() {
  if (!props.kbId) {
    members.value = [];
    return;
  }
  loading.value = true;
  try {
    const data = await api(`/api/knowledge-bases/${props.kbId}/members`);
    members.value = data.items || [];
  } catch (err) {
    members.value = [];
  } finally {
    loading.value = false;
  }
}

async function addMember() {
  const userId = Number(memberUserId.value);
  if (!userId) {
    showToast('请输入用户 ID', true);
    return;
  }
  try {
    await api(`/api/knowledge-bases/${props.kbId}/members`, {
      method: 'POST',
      body: JSON.stringify({ userId, role: memberRole.value })
    });
    memberUserId.value = '';
    showToast('成员已添加');
    await loadMembers();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function removeMember(userId) {
  if (!window.confirm('确认移除该成员？')) return;
  try {
    await api(`/api/knowledge-bases/${props.kbId}/members/${userId}`, { method: 'DELETE' });
    showToast('成员已移除');
    await loadMembers();
  } catch (err) {
    showToast(err.message, true);
  }
}

watch(() => props.kbId, loadMembers, { immediate: true });
</script>

<template>
  <div v-if="kbId" class="member-panel">
    <h3>成员</h3>
    <div class="member-list">
      <div v-if="!members.length && !loading" class="empty small">暂无成员</div>
      <div v-for="member in members" :key="member.userId" class="member-row">
        <span>#{{ member.userId }} <b>{{ member.role }}</b></span>
        <button v-if="member.role !== 'OWNER'" @click="removeMember(member.userId)">移除</button>
      </div>
    </div>
    <div class="member-add">
      <input v-model="memberUserId" type="number" min="1" placeholder="用户 ID">
      <select v-model="memberRole">
        <option>MEMBER</option>
        <option>OWNER</option>
      </select>
      <button class="secondary" @click="addMember">添加</button>
    </div>
  </div>
</template>

<style scoped>
.member-panel {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
  display: grid;
  gap: 8px;
}

.member-panel h3 {
  margin: 0;
  font-size: 14px;
}

.member-list {
  display: grid;
  gap: 6px;
}

.member-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  background: #fafcfd;
  border: 1px solid var(--line);
  border-radius: 6px;
}

.member-add {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 6px;
}
</style>
