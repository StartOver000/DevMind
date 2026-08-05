import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api, apiText, formatTime, getToken, setToken, setCurrentUserId, getCurrentUserId } from '@/api/client';

describe('api client', () => {
  beforeEach(() => {
    localStorage.clear();
    setCurrentUserId(1);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('formatTime 格式化后端时间', () => {
    expect(formatTime('2026-08-04T10:20:30.123')).toBe('2026-08-04 10:20:30');
    expect(formatTime('2026-08-04')).toBe('2026-08-04');
    expect(formatTime(null)).toBe('-');
    expect(formatTime('')).toBe('-');
  });

  it('formatTime 将 UTC/ISO 时间戳转为本地 24 小时制', () => {
    const utc = '2026-08-05T06:00:00.000Z';
    const d = new Date(utc);
    const pad = (n) => String(n).padStart(2, '0');
    const expected = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    // 不写死具体时刻，避免依赖测试机时区；断言已转为本地时间且为 24 小时制
    expect(formatTime(utc)).toBe(expected);
    expect(formatTime(utc)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    // 不能是 ISO 时间戳原样（含 T 或 Z）
    expect(formatTime(utc)).not.toContain('T');
    expect(formatTime(utc)).not.toContain('Z');
  });

  it('formatTime 对 Date 对象同样转本地 24 小时制', () => {
    const d = new Date(2026, 7, 5, 14, 6, 54); // 本地时间 2026-08-05 14:06:54
    expect(formatTime(d)).toBe('2026-08-05 14:06:54');
  });

  it('token 存取', () => {
    expect(getToken()).toBeNull();
    setToken('abc123');
    expect(getToken()).toBe('abc123');
    setToken(null);
    expect(getToken()).toBeNull();
  });

  it('api 自动附加 X-User-Id 与 Authorization header', async () => {
    setToken('tok-1');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ items: [] })
    });
    global.fetch = fetchMock;

    const data = await api('/api/users');
    expect(data).toEqual({ items: [] });
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/users');
    expect(options.headers['X-User-Id']).toBe('1');
    expect(options.headers['Authorization']).toBe('Bearer tok-1');
  });

  it('api 对 JSON body 设置 Content-Type', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => 'null'
    });
    global.fetch = fetchMock;

    await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ a: 1 }) });
    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers['Content-Type']).toBe('application/json');
  });

  it('api 对 FormData 不设置 Content-Type', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => '{}'
    });
    global.fetch = fetchMock;

    await api('/api/upload', { method: 'POST', body: new FormData() });
    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers['Content-Type']).toBeUndefined();
  });

  it('api 非 2xx 抛出带 message 的错误', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      text: async () => JSON.stringify({ message: '未登录' })
    });
    global.fetch = fetchMock;

    await expect(api('/api/knowledge-bases')).rejects.toThrow('未登录');
  });

  it('api 非 2xx 且无 message 时回退到 HTTP 状态码', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => ''
    });
    global.fetch = fetchMock;

    await expect(api('/api/x')).rejects.toThrow('HTTP 500');
  });

  it('api 返回非 JSON 文本时不解析', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => 'plain text'
    });
    global.fetch = fetchMock;

    const data = await api('/api/x');
    expect(data).toBeNull();
  });

  it('apiText 返回纯文本（不 JSON 解析），并附加用户头', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => '# markdown 内容'
    });
    global.fetch = fetchMock;

    const text = await apiText('/api/documents/1/content');
    expect(text).toBe('# markdown 内容');
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/documents/1/content');
    expect(options.headers['X-User-Id']).toBe('1');
  });

  it('apiText 非 2xx 抛出 HTTP 错误', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => 'server error'
    });
    global.fetch = fetchMock;

    await expect(apiText('/api/documents/1/content')).rejects.toThrow('HTTP 500');
  });
});
