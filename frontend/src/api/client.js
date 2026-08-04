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

/** 格式化后端时间字符串为 yyyy-MM-dd HH:mm:ss */
export function formatTime(value) {
  if (!value) return '-';
  return String(value).replace('T', ' ').slice(0, 19);
}
