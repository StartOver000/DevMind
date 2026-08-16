<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Listbox, ListboxButton, ListboxOptions, ListboxOption, Menu, MenuButton, MenuItems, MenuItem } from '@headlessui/vue';
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
const currentLabel = computed(() =>
  currentUser.value?.displayName || currentUser.value?.username || '选择用户'
);

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

function onUserChange(value) {
  setCurrentUserId(Number(value));
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
      <!-- 前台：业务人员 4 件事（产品面孔收敛 P0，见 docs/product/产品审视-20260816.md） -->
      <router-link to="/kb" class="tab" active-class="active">知识库</router-link>
      <router-link to="/chat" class="tab" active-class="active">问答</router-link>
      <router-link to="/workflows" class="tab" active-class="active">流程</router-link>
      <router-link to="/skills" class="tab" active-class="active">技能</router-link>

      <!-- 管理：技术/管理功能收进后台入口，业务人员不被工具噪音打扰 -->
      <Menu as="div" class="admin-menu">
        <MenuButton class="tab admin-btn">管理 ▾</MenuButton>
        <Transition name="dropdown">
          <MenuItems class="admin-items">
            <MenuItem v-slot="{ active }">
              <router-link to="/tools" class="admin-item" :class="{ active }">接口</router-link>
            </MenuItem>
            <MenuItem v-slot="{ active }">
              <router-link to="/sql" class="admin-item" :class="{ active }">SQL 诊断</router-link>
            </MenuItem>
            <MenuItem v-slot="{ active }">
              <router-link to="/usage" class="admin-item" :class="{ active }">用量</router-link>
            </MenuItem>
            <MenuItem v-slot="{ active }">
              <router-link to="/team" class="admin-item" :class="{ active }">团队</router-link>
            </MenuItem>
            <MenuItem v-slot="{ active }">
              <router-link to="/eval" class="admin-item" :class="{ active }">评估</router-link>
            </MenuItem>
          </MenuItems>
        </Transition>
      </Menu>
    </nav>

    <div class="right-group">
      <Listbox :model-value="session.userId" @update:model-value="onUserChange">
        <div class="user-picker" title="切换当前用户">
          <ListboxButton class="user-btn">
            <span class="avatar">{{ currentInitial }}</span>
            <span class="user-name">{{ currentLabel }}</span>
            <svg class="chevron" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 4l4 4 4-4"/></svg>
          </ListboxButton>

          <Transition name="dropdown">
            <ListboxOptions class="user-options">
              <ListboxOption
                v-for="u in users"
                :key="u.id"
                :value="u.id"
                v-slot="{ active, selected }"
              >
                <div class="user-option" :class="{ active }">
                  <span class="opt-avatar">{{ (u.displayName || u.username || '?').charAt(0).toUpperCase() }}</span>
                  <span class="opt-name">{{ u.displayName || u.username }} <em>#{{ u.id }}</em></span>
                  <svg v-if="selected" class="check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
                </div>
              </ListboxOption>
            </ListboxOptions>
          </Transition>
        </div>
      </Listbox>

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

/* 管理下拉（产品面孔收敛 P0：技术/管理功能收进后台入口） */
.admin-menu {
  position: relative;
}

.admin-btn {
  cursor: pointer;
  user-select: none;
}

.admin-items {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  min-width: 130px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 5px;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.12);
  z-index: 30;
  outline: none;
  display: grid;
  gap: 2px;
}

.admin-item {
  display: block;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--text);
  text-decoration: none;
  white-space: nowrap;
}

.admin-item:hover,
.admin-item.active {
  background: var(--alt-bg);
  color: var(--accent);
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
  position: relative;
}

.user-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 10px 3px 3px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--alt-bg);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}

.user-btn:hover {
  border-color: var(--accent);
}

.user-btn:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
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

.user-name {
  font-weight: 600;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chevron {
  width: 12px;
  height: 12px;
  color: var(--muted);
  flex-shrink: 0;
  transition: transform 0.15s;
}

.user-picker[data-headlessui-state="open"] .chevron {
  transform: rotate(180deg);
}

.user-options {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  min-width: 230px;
  max-height: 320px;
  overflow: auto;
  list-style: none;
  margin: 0;
  padding: 6px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.22);
  z-index: 60;
}

.user-option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 7px;
  cursor: pointer;
  color: var(--text);
}

.user-option.active {
  background: var(--accent-weak);
  color: var(--accent);
}

.opt-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.opt-name {
  flex: 1;
  font-weight: 500;
}

.opt-name em {
  color: var(--muted);
  font-style: normal;
  font-size: 12px;
}

.check {
  width: 14px;
  height: 14px;
  color: var(--accent);
  flex-shrink: 0;
}

.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity 0.12s, transform 0.12s;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
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
