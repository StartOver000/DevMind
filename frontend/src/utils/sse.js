/**
 * SSE 客户端工具：基于 fetch POST 的流式读取与事件分发。
 * 协议（与后端 SseEmitter 对齐）：
 * - event: delta —— 回答文本分块（纯文本）
 * - event: meta  —— 会话 ID + 引用来源（JSON）
 * - event: trace —— Agent 工具轨迹（JSON）
 * - event: done  —— 结束（JSON）
 * - event: error —— 出错（JSON）
 */

/** 把一段 SSE 文本按空行分隔成事件块数组 */
export function splitSseEvents(text) {
  return String(text || '').split(/\r?\n\r?\n/).filter((b) => b.trim().length > 0);
}

/** 解析单个 SSE 事件块 → { event, data }（data 为原始文本，可能跨多行 data:） */
export function parseSseEvent(block) {
  let event = 'message';
  const dataLines = [];
  for (const line of String(block).split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
  }
  return { event, data: dataLines.join('\n') };
}

/** 尝试把 payload 解析为 JSON，失败则返回原文本 */
export function parsePayload(data) {
  if (data === '' || data == null) return data;
  try {
    return JSON.parse(data);
  } catch (e) {
    return data;
  }
}

/** 事件名 → handler 键：delta → onDelta */
export function handlerKey(event) {
  return 'on' + event.charAt(0).toUpperCase() + event.slice(1);
}

/**
 * 通过 fetch POST 建立 SSE 连接并按事件分发。
 * @param {string} url 请求地址
 * @param {object} body 请求体（自动 JSON 序列化）
 * @param {object} handlers { onMeta, onDelta, onTrace, onDone, onError, onMessage }
 * @param {object} options { headers, signal }
 */
export async function streamFetch(url, body, handlers = {}, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  const res = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal: options.signal
  });
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const data = await res.json();
      if (data && data.message) message = data.message;
    } catch (e) {
      /* 忽略非 JSON 错误体 */
    }
    const err = new Error(message);
    err.status = res.status;
    throw err;
  }
  // 老环境无 ReadableStream：按普通 JSON 一次性处理
  if (!res.body || typeof res.body.getReader !== 'function') {
    const data = await res.json();
    const fallback = handlers.onDone || handlers.onMessage;
    if (fallback) fallback(data);
    return;
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      dispatchBlock(block, handlers);
    }
  }
  if (buffer.trim()) {
    dispatchBlock(buffer, handlers);
  }
}

function dispatchBlock(block, handlers) {
  const { event, data } = parseSseEvent(block);
  if (event === 'error') {
    const payload = parsePayload(data);
    const err = new Error((payload && payload.message) ? payload.message : '流式响应出错');
    if (handlers.onError) {
      handlers.onError(err);
      return;
    }
    throw err;
  }
  const handler = handlers[handlerKey(event)] || handlers.onMessage;
  if (handler) {
    handler(parsePayload(data));
  }
}
