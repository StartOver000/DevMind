/**
 * 统一请求封装
 * - 自动附加 Authorization Bearer Token 与 X-User-Id
 * - JSON 序列化与错误处理
 * - 表单上传（FormData）透传
 */
const TOKEN_KEY = 'devmind_token';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

let currentUserId = Number(localStorage.getItem('devmind_user_id') || 1);

export function setCurrentUserId(id) {
  currentUserId = Number(id);
  localStorage.setItem('devmind_user_id', String(currentUserId));
}

export function getCurrentUserId() {
  return currentUserId;
}

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  headers['X-User-Id'] = String(currentUserId);
  const token = getToken();
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(path, { ...options, headers });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch (e) {
    data = null;
  }
  if (!res.ok) {
    const err = new Error(data && data.message ? data.message : `HTTP ${res.status}`);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

/**
 * 纯文本请求：后端返回非 JSON（如文档内容 /content）时使用，
 * 避免 api() 的 JSON.parse 把文本解析失败后变成 null。
 */
export async function apiText(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  headers['X-User-Id'] = String(currentUserId);
  const token = getToken();
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  const res = await fetch(path, { ...options, headers });
  const text = await res.text();
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return text;
}

/**
 * 格式化时间为本地 24 小时制 yyyy-MM-dd HH:mm:ss。
 * - ISO/UTC（带 Z）自动转换为本地时区（与"时间戳"问题一致：给人看的必须是本地时间）
 * - 纯日期（无时间部分）原样返回
 * - 非法值回退为截断字符串
 */
export function formatTime(value) {
  if (!value) return '-';
  const s = String(value);
  const pad = (n) => String(n).padStart(2, '0');
  // 纯日期（无 T 且无冒号）：原样返回
  if (!s.includes('T') && !s.includes(':')) return s;
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) {
    return s.replace('T', ' ').slice(0, 19);
  }
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
