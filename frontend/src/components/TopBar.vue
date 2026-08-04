<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { api, getToken, setToken, setCurrentUserId, getCurrentUserId } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';

const router = useRouter();
const users = ref([]);
const loginUser = ref('');
const loginPass = ref('');
const theme = ref(localStorage.getItem('devmind_theme') || 'light');

function applyTheme(value) {
  document.documentElement.dataset.theme = value;
  localStorage.setItem('devmind_theme', value);
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark';
  applyTheme(theme.value);
}

onMounted(() => applyTheme(theme.value));

const isLoggedIn = () => Boolean(getToken());

async function loadUsers() {
  try {
    const data = await api('/api/users');
    users.value = data.items || [];
    const current = getCurrentUserId();
    if (!users.value.some((u) => u.id === current)) {
      setCurrentUserId(users.value[0]?.id || 1);
    }
    session.userId = getCurrentUserId();
  } catch (err) {
    // 未登录时用户列表可能不可用，静默处理
    users.value = [];
  }
}

function onUserChange(event) {
  setCurrentUserId(Number(event.target.value));
  session.userId = getCurrentUserId();
  session.requestReload();
}

async function login() {
  const username = loginUser.value.trim();
  const password = loginPass.value;
  if (!username || !password) {
    showToast('请输入用户名和密码', true);
    return;
  }
  try {
    const data = await api('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
    setToken(data.token);
    setCurrentUserId(data.userId);
    session.userId = data.userId;
    loginUser.value = '';
    loginPass.value = '';
    await loadUsers();
    session.requestReload();
    showToast('登录成功');
  } catch (err) {
    showToast(err.message, true);
  }
}

function logout() {
  setToken(null);
  session.userId = 1;
  setCurrentUserId(1);
  session.requestReload();
  router.push('/kb');
}

onMounted(loadUsers);
</script>

<template>
  <header class="topbar">
    <div class="brand">DevMind</div>
    <nav class="tabs">
      <router-link to="/kb" class="tab" active-class="active">知识库</router-link>
      <router-link to="/chat" class="tab" active-class="active">问答</router-link>
      <router-link to="/sql" class="tab" active-class="active">SQL 诊断</router-link>
      <router-link to="/usage" class="tab" active-class="active">用量</router-link>
      <router-link to="/team" class="tab" active-class="active">团队</router-link>
      <router-link to="/eval" class="tab" active-class="active">评估</router-link>
    </nav>
    <label class="user-picker">
      当前用户
      <select :value="session.userId" @change="onUserChange">
        <option v-for="u in users" :key="u.id" :value="u.id">
          {{ u.displayName || u.username }} (#{{ u.id }})
        </option>
      </select>
    </label>
    <button class="secondary theme-toggle" @click="toggleTheme">
      {{ theme === 'dark' ? '浅色' : '深色' }}
    </button>
    <div v-if="!isLoggedIn()" class="auth-box">
      <input v-model="loginUser" placeholder="用户名" autocomplete="username">
      <input v-model="loginPass" type="password" placeholder="密码" autocomplete="current-password">
      <button class="secondary" @click="login">登录</button>
    </div>
    <div v-else class="auth-box">
      <button class="secondary" @click="logout">退出</button>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 20px;
  height: 56px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
}

.brand {
  font-size: 18px;
  font-weight: 700;
  color: var(--accent);
  white-space: nowrap;
}

.tabs {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.theme-toggle {
  white-space: nowrap;
}

.tab {
  border: 0;
  background: transparent;
  color: var(--muted);
  padding: 8px 14px;
  border-radius: 6px;
  text-decoration: none;
}

.tab.active {
  background: var(--accent-weak);
  color: var(--accent);
  font-weight: 600;
}

.user-picker {
  margin-left: auto;
  width: 220px;
  color: var(--muted);
}

.auth-box {
  display: flex;
  gap: 6px;
  align-items: center;
}

.auth-box input {
  width: 130px;
}

@media (max-width: 900px) {
  .topbar {
    flex-wrap: wrap;
    height: auto;
    padding: 10px 12px;
    gap: 10px;
  }

  .user-picker {
    margin-left: 0;
    width: 100%;
  }

  .auth-box {
    width: 100%;
    flex-wrap: wrap;
  }

  .auth-box input {
    flex: 1;
    width: auto;
  }
}

@media (max-width: 700px) {
  .tabs {
    order: 3;
    width: 100%;
    overflow-x: auto;
    flex-wrap: nowrap;
    -webkit-overflow-scrolling: touch;
  }

  .tab {
    padding: 8px 12px;
    white-space: nowrap;
  }
}
</style>
