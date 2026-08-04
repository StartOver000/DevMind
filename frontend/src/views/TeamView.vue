<script setup>
import { ref, watch, onMounted } from 'vue';
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';

const teams = ref([]);
const users = ref([]);
const expandedId = ref(null);
const newName = ref('');
const newDesc = ref('');
const creating = ref(false);
const addUserId = ref('');
const addRole = ref('MEMBER');

async function loadTeams() {
  try {
    const data = await api('/api/teams');
    teams.value = data.items || [];
  } catch (err) {
    showToast(err.message, true);
  }
}

async function loadUsers() {
  try {
    const data = await api('/api/users');
    users.value = data.items || [];
  } catch (err) {
    users.value = [];
  }
}

async function createTeam() {
  const name = newName.value.trim();
  if (!name) {
    showToast('请输入团队名称', true);
    return;
  }
  creating.value = true;
  try {
    const team = await api('/api/teams', {
      method: 'POST',
      body: JSON.stringify({ name, description: newDesc.value.trim() || null })
    });
    newName.value = '';
    newDesc.value = '';
    await loadTeams();
    expandedId.value = team.id;
    await loadDetail(team.id);
    showToast('团队创建成功');
  } catch (err) {
    showToast(err.message, true);
  } finally {
    creating.value = false;
  }
}

async function toggleExpand(team) {
  expandedId.value = expandedId.value === team.id ? null : team.id;
  if (expandedId.value) {
    await loadDetail(team.id);
  }
}

async function loadDetail(id) {
  try {
    const detail = await api(`/api/teams/${id}`);
    const t = teams.value.find((x) => x.id === id);
    if (t) {
      t.members = detail.members || [];
    }
  } catch (err) {
    showToast(err.message, true);
  }
}

async function addMember(team) {
  const userId = Number(addUserId.value);
  if (!userId) {
    showToast('请选择用户', true);
    return;
  }
  try {
    await api(`/api/teams/${team.id}/members`, {
      method: 'POST',
      body: JSON.stringify({ userId, role: addRole.value })
    });
    addUserId.value = '';
    await loadDetail(team.id);
    showToast('成员已添加');
  } catch (err) {
    showToast(err.message, true);
  }
}

async function removeMember(team, member) {
  try {
    await api(`/api/teams/${team.id}/members/${member.userId}`, { method: 'DELETE' });
    await loadDetail(team.id);
    showToast('成员已移除');
  } catch (err) {
    showToast(err.message, true);
  }
}

async function deleteTeam(team) {
  if (!confirm(`确认删除团队「${team.name}」？`)) return;
  try {
    await api(`/api/teams/${team.id}`, { method: 'DELETE' });
    expandedId.value = null;
    await loadTeams();
    showToast('团队已删除');
  } catch (err) {
    showToast(err.message, true);
  }
}

function canManage(team) {
  return team.members?.some((m) => m.userId === session.userId && m.role === 'OWNER') ?? false;
}

watch(
  () => session.reloadKey,
  () => {
    loadTeams();
    loadUsers();
  }
);

onMounted(() => {
  loadTeams();
  loadUsers();
});
</script>

<template>
  <div class="team-stack">
    <div class="panel">
      <div class="panel-header"><h2>团队管理</h2></div>
      <p class="hint">
        团队用于多租户隔离：团队内成员共享团队知识库，团队之间的数据互不可见。
        创建团队后，可在知识库创建时选择归属团队。
      </p>
      <form class="form-stack" @submit.prevent="createTeam">
        <div class="form-row">
          <input v-model="newName" maxlength="100" placeholder="团队名称" required>
          <input v-model="newDesc" maxlength="500" placeholder="描述（可选）">
          <button type="submit" class="primary" :disabled="creating">
            {{ creating ? '创建中…' : '创建团队' }}
          </button>
        </div>
      </form>
    </div>

    <div class="panel">
      <div class="panel-header"><h2>我的团队</h2></div>
      <div v-if="!teams.length" class="empty small">还没有团队，先创建一个吧</div>
      <div v-for="team in teams" :key="team.id" class="team-card">
        <div class="team-card-head" @click="toggleExpand(team)">
          <div class="team-title">
            <b>{{ team.name }}</b>
            <span v-if="team.description" class="muted"> · {{ team.description }}</span>
          </div>
          <span class="muted small">OWNER #{{ team.ownerId }} · {{ formatTime(team.createdTime) }}</span>
          <span class="caret">{{ expandedId === team.id ? '▲' : '▼' }}</span>
        </div>
        <div v-if="expandedId === team.id" class="team-detail">
          <div class="table-wrap">
            <table>
              <thead>
                <tr><th>用户</th><th>角色</th><th>加入时间</th><th></th></tr>
              </thead>
              <tbody>
                <tr v-for="m in (team.members || [])" :key="m.userId">
                  <td>{{ m.username }} (#{{ m.userId }})</td>
                  <td>{{ m.role === 'OWNER' ? '所有者' : '成员' }}</td>
                  <td>{{ formatTime(m.createdTime) }}</td>
                  <td>
                    <button
                      v-if="m.role !== 'OWNER' && canManage(team)"
                      class="danger-link"
                      @click="removeMember(team, m)"
                    >移除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="canManage(team)" class="form-row add-row">
            <select v-model="addUserId">
              <option value="">选择用户…</option>
              <option v-for="u in users" :key="u.id" :value="u.id">
                {{ u.displayName || u.username }} (#{{ u.id }})
              </option>
            </select>
            <select v-model="addRole">
              <option value="MEMBER">成员</option>
              <option value="OWNER">所有者</option>
            </select>
            <button class="secondary" @click="addMember(team)">添加成员</button>
          </div>
          <div v-if="canManage(team)" class="team-actions">
            <button class="danger" @click="deleteTeam(team)">删除团队</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.team-stack {
  display: grid;
  gap: 16px;
}

.hint {
  color: var(--muted);
  font-size: 13px;
  margin-bottom: 12px;
}

.form-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.form-row input {
  flex: 1;
  min-width: 160px;
}

.team-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  margin-bottom: 10px;
}

.team-card-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  cursor: pointer;
  background: var(--bg);
}

.team-card-head:hover {
  background: var(--accent-weak);
}

.team-title {
  flex: 1;
}

.caret {
  color: var(--muted);
}

.team-detail {
  border-top: 1px solid var(--line);
  padding: 12px 14px;
  display: grid;
  gap: 10px;
}

.add-row {
  align-items: center;
}

.team-actions {
  text-align: right;
}

.danger-link {
  color: var(--danger, #d33);
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px 4px;
  font-size: 13px;
}
</style>
