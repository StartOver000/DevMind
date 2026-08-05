import { describe, it, expect, vi } from 'vitest';
import {
  splitSseEvents,
  parseSseEvent,
  parsePayload,
  handlerKey,
  streamFetch
} from '@/utils/sse';

/** 构造一个可控的 ReadableStream（按块吐出文本） */
function streamOf(parts) {
  return new ReadableStream({
    start(controller) {
      for (const part of parts) {
        controller.enqueue(new TextEncoder().encode(part));
      }
      controller.close();
    }
  });
}

function okResponse(body) {
  return {
    ok: true,
    status: 200,
    body: streamOf(body)
  };
}

describe('sse utils', () => {
  it('splitSseEvents 按空行切分事件块', () => {
    const blocks = splitSseEvents('event: delta\ndata: 你好\n\nevent: done\ndata: {"ok":true}');
    expect(blocks).toHaveLength(2);
    expect(blocks[0]).toContain('event: delta');
    expect(blocks[1]).toContain('event: done');
  });

  it('parseSseEvent 解析 event 与多行 data', () => {
    const { event, data } = parseSseEvent('event: delta\ndata: 第一行\ndata: 第二行');
    expect(event).toBe('delta');
    expect(data).toBe('第一行\n第二行');
  });

  it('parseSseEvent 缺省 event 为 message', () => {
    const { event, data } = parseSseEvent('data: 内容');
    expect(event).toBe('message');
    expect(data).toBe('内容');
  });

  it('parsePayload JSON 解析与纯文本兜底', () => {
    expect(parsePayload('{"a":1}')).toEqual({ a: 1 });
    expect(parsePayload('纯文本')).toBe('纯文本');
    expect(parsePayload('')).toBe('');
  });

  it('handlerKey 事件名转回调键', () => {
    expect(handlerKey('delta')).toBe('onDelta');
    expect(handlerKey('trace')).toBe('onTrace');
    expect(handlerKey('message')).toBe('onMessage');
  });

  it('streamFetch 完整分发 meta/delta/done', async () => {
    const events = [];
    global.fetch = vi.fn().mockResolvedValue(okResponse([
      'event: meta\ndata: {"conversationId":7,"references":[]}\n\n',
      'event: delta\ndata: 你好',
      '，世界。\n\n',
      'event: done\ndata: {"ok":true}\n\n'
    ]));

    await streamFetch('/api/chat/stream', { question: 'q' }, {
      onMeta: (m) => events.push(['meta', m.conversationId]),
      onDelta: (c) => events.push(['delta', c]),
      onDone: (d) => events.push(['done', d.ok])
    });

    expect(events).toEqual([
      ['meta', 7],
      ['delta', '你好，世界。'],
      ['done', true]
    ]);
    // 校验请求参数
    const [url, options] = global.fetch.mock.calls[0];
    expect(url).toBe('/api/chat/stream');
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body)).toEqual({ question: 'q' });
  });

  it('streamFetch 分发 Agent trace 事件', async () => {
    const traces = [];
    global.fetch = vi.fn().mockResolvedValue(okResponse([
      'event: trace\ndata: {"tool":"kb_search","ok":true,"costMs":12}\n\n',
      'event: done\ndata: {"conversationId":3}\n\n'
    ]));

    await streamFetch('/api/agent/chat/stream', { question: 'q' }, {
      onTrace: (t) => traces.push(t),
      onDone: () => {}
    });

    expect(traces).toHaveLength(1);
    expect(traces[0].tool).toBe('kb_search');
  });

  it('streamFetch error 事件触发 onError', async () => {
    const errors = [];
    global.fetch = vi.fn().mockResolvedValue(okResponse([
      'event: error\ndata: {"message":"模型失败"}\n\n'
    ]));

    await streamFetch('/api/x', {}, {
      onError: (e) => errors.push(e.message)
    });

    expect(errors).toEqual(['模型失败']);
  });

  it('streamFetch 非 2xx 抛出 HTTP 错误', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({ message: '服务器错误' })
    });

    await expect(streamFetch('/api/x', {})).rejects.toThrow('服务器错误');
  });

  it('streamFetch 跨块缓冲（事件被拆到多次 read）', async () => {
    const deltas = [];
    global.fetch = vi.fn().mockResolvedValue(okResponse([
      'event: delta\ndata: 分',
      '块内容\n\n',
      'event: done\ndata: {',
      '"ok":true}\n\n'
    ]));

    await streamFetch('/api/x', {}, {
      onDelta: (c) => deltas.push(c),
      onDone: (d) => deltas.push(['done', d.ok])
    });

    expect(deltas).toEqual(['分块内容', ['done', true]]);
  });
});
