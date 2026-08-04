<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { api, getToken, setToken, setCurrentUserId, getCurrentUserId } from '@/api/client';
import { showToast } from '@/stores/toast';
import { session } from '@/stores/session';

const router = useRouter();
const users = ref([]);
const loginUser = ref('');
const loginPass = ref('');
const theme = ref(localStorage.getItem('devmind_theme') || 'light');

// 当前选中用户（用于头像与显示名）
const currentUser = computed(() =>
  users.value.find((u) => u.id === session.userId)
);
const currentInitial = computed(() => {
  const name = currentUser.value?.displayName || currentUser.value?.username || '';
  return name ? name.charAt(0).toUpperCase() : '?';  
});

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
    <div class="brand">
      <span class="brand-dot"></span>DevMind
    </div>
    <nav class="tabs">
      <router-link to="/kb" class="tab" active-class="active">知识库</router-link>
      <router-link to="/chat" class="tab" active-class="active">问答</router-link>
      <router-link to="/sql" class="tab" active-class="active">SQL 诊断</router-link>
      <router-link to="/usage" class="tab" active-class="active">用量</router-link>
      <router-link to="/team" class="tab" active-class="active">团队</router-link>
      <router-link to="/eval" class="tab" active-class="active">评估</router-link>
    </nav>

    <div class="right-group">
      <div class="user-picker" title="切换当前用户">
        <span class="avatar">{{ currentInitial }}</span>
        <select
          class="user-select"
          :value="session.userId"
          @change="onUserChange"
          aria-label="切换当前用户"
        >
          <option v-for="u in users" :key="u.id" :value="u.id">
            {{ u.displayName || u.username }} (#{{ u.id }})
          </option>
        </select>
      </div>

      <button class="theme-toggle" @click="toggleTheme" :title="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'">
        <svg v-if="theme === 'dark'" class="theme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>
        <svg v-else class="theme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
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
  gap: 20px;
  padding: 0 20px;
  height: 56px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 700;
  color: var(--accent);
  white-space: nowrap;
  letter-spacing: 0.3px;
}

.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--accent), color-mix(in srgb, var(--accent) 55%, var(--ok)));
  box-shadow: 0 0 6px color-mix(in srgb, var(--accent) 50%, transparent);
}

.tabs {
  display: flex;
  gap: 2px;
  flex-wrap: wrap;
}

.tab {
  border: 0;
  background: transparent;
  color: var(--muted);
  padding: 7px 13px;
  border-radius: 7px;
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}

.tab:hover {
  background: var(--alt-bg);
  color: var(--text);
}

.tab.active {
  background: var(--accent-weak);
  color: var(--accent);
  font-weight: 600;
}

/* 右侧功能区：用户选择 + 主题 + 登录态 */
.right-group {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
  flex-wrap: wrap;
}

.user-picker {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 6px 3px 3px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--alt-bg);
  transition: border-color 0.15s;
}

.user-picker:hover {
  border-color: var(--accent);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  user-select: none;
  flex-shrink: 0;
}

.user-select {
  appearance: none;
  -webkit-appearance: none;
  border: 0;
  background: transparent;
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
  padding: 4px 22px 4px 2px;
  cursor: pointer;
  max-width: 170px;
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 12 12'%3E%3Cpath d='M2 4l4 4 4-4' fill='none' stroke='%238b98a6' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 4px center;
  background-size: 12px;
}

.user-select:hover {
  color: var(--accent);
}

.theme-toggle {
  white-space: nowrap;
  padding: 7px 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.theme-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
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

  .right-group {
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
