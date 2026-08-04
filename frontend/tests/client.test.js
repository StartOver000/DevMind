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
