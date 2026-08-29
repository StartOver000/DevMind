// DevMind MCP Server 冒烟测试（W1 验收脚本，零依赖 Node ≥ 18）
// 用法：先启动应用（mock 模型即可）——
//   DEVMIND_MCP_SERVER_ENABLED=true SERVER_PORT=18080 DEVMIND_EMBEDDING_DIMENSIONS=1024 \
//   mvn spring-boot:run -Dspring-boot.run.profiles=mock
//   （DEVMIND_EMBEDDING_DIMENSIONS 需与 document_chunk.embedding 列维度一致，默认库为 1024）
// 然后：node scripts/mcp/mcp-smoke.mjs
// 验收：SSE 握手 → tools/list 含 kb_search/kb_chat → 建库传文档 → kb_search 真实命中
//       → kb_chat 返回回答与引用 → 审计日志出现 mcp.kb_search / mcp.kb_chat。
const BASE = process.env.DEVMIND_SMOKE_BASE || 'http://127.0.0.1:18080';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function main() {
  // 1) SSE 连接，取 message endpoint
  const sse = await fetch(BASE + '/sse');
  if (!sse.ok || !sse.body) throw new Error('SSE 连接失败: ' + sse.status);
  const reader = sse.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '', endpoint = null;
  const pending = new Map(); // id -> resolve

  const pump = (async () => {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const raw = buffer.slice(0, idx); buffer = buffer.slice(idx + 2);
        let eventName = '', data = '';
        for (const line of raw.split('\n')) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim();
          if (line.startsWith('data:')) data += line.slice(5).trim();
        }
        if (eventName === 'endpoint') { endpoint = data; console.log('[SSE] endpoint =', endpoint); continue; }
        if (eventName === 'message' && data) {
          try {
            const msg = JSON.parse(data);
            if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
          } catch {}
        }
      }
    }
  })();

  for (let i = 0; i < 50 && !endpoint; i++) await sleep(100);
  if (!endpoint) throw new Error('没收到 endpoint 事件');
  const post = async (body) => (await fetch(BASE + endpoint, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  })).status;
  const rpc = async (id, method, params) => {
    const p = new Promise(res => pending.set(id, res));
    const st = await post({ jsonrpc: '2.0', id, method, params });
    if (st !== 200 && st !== 202) throw new Error(`POST ${method} -> HTTP ${st}`);
    const msg = await Promise.race([p, sleep(8000).then(() => { throw new Error(method + ' 超时'); })]);
    if (msg.error) throw new Error(method + ' -> ' + JSON.stringify(msg.error));
    return msg.result;
  };

  // 2) initialize + initialized 通知
  const init = await rpc(1, 'initialize', {
    protocolVersion: '2024-11-05', capabilities: {},
    clientInfo: { name: 'devmind-smoke', version: '0.0.1' }
  });
  console.log('[MCP] server:', init.serverInfo?.name, init.serverInfo?.version, '| protocol:', init.protocolVersion);
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' });

  // 3) tools/list
  const list = await rpc(2, 'tools/list', {});
  const names = list.tools.map(t => t.name);
  console.log('[MCP] tools:', names.join(', '));
  if (!names.includes('kb_search') || !names.includes('kb_chat')) throw new Error('工具缺失: ' + names);

  // 4) REST 造数据：建库 + 传一篇带特征词的 md
  const kbRes = await (await fetch(BASE + '/api/knowledge-bases', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-User-Id': '1' },
    body: JSON.stringify({ name: 'mcp-smoke-' + Date.now(), description: 'MCP 联调临时库' })
  })).json();
  const kbId = kbRes.id ?? kbRes.knowledgeBaseId;
  console.log('[REST] 知识库 id =', kbId);

  const md = '# 冒烟测试\n\n特征词 quantum-zebra-42：DevMind 通过 MCP 暴露 kb_search 工具，用于跨平台检索验证。\n'.repeat(6);
  const form = new FormData();
  form.append('file', new Blob([md], { type: 'text/markdown' }), 'mcp-smoke.md');
  const up = await fetch(`${BASE}/api/knowledge-bases/${kbId}/documents`, {
    method: 'POST', headers: { 'X-User-Id': '1' }, body: form
  });
  console.log('[REST] 文档上传:', up.status);

  // 5) tools/call kb_search（轮询等异步解析完成）
  let searchResult = null;
  for (let i = 0; i < 15; i++) {
    await sleep(1500);
    const r = await rpc(10 + i, 'tools/call', {
      name: 'kb_search',
      arguments: { knowledgeBaseId: kbId, question: 'quantum-zebra-42 特征词是什么', topK: 5 }
    });
    const text = r.content?.[0]?.text ?? '';
    const parsed = JSON.parse(text);
    if (parsed.hitCount > 0) { searchResult = parsed; break; }
    searchResult = parsed;
  }
  console.log('[MCP] kb_search hitCount =', searchResult?.hitCount,
    '| 首条:', searchResult?.hits?.[0]?.documentName, '| score =', searchResult?.hits?.[0]?.score);

  // 6) tools/call kb_chat
  const chat = await rpc(100, 'tools/call', {
    name: 'kb_chat', arguments: { knowledgeBaseId: kbId, question: '用一句话说明 quantum-zebra-42' }
  });
  const chatText = chat.content?.[0]?.text ?? '';
  const chatJson = JSON.parse(chatText);
  console.log('[MCP] kb_chat answer =', String(chatJson.answer).slice(0, 80), '| 引用数 =', (chatJson.references || []).length);

  // 7) 审计验证
  const audit = await (await fetch(BASE + '/api/audit-logs?limit=10', { headers: { 'X-User-Id': '1' } })).json();
  const actions = JSON.stringify(audit).match(/mcp\.[a-z_]+/g) || [];
  console.log('[AUDIT] mcp 动作:', [...new Set(actions)].join(', '));

  const pass = searchResult?.hitCount > 0 && actions.includes('mcp.kb_search') && actions.includes('mcp.kb_chat');
  console.log(pass ? 'SMOKE PASS' : 'SMOKE FAIL');
  process.exit(pass ? 0 : 1);
}

main().catch(e => { console.error('SMOKE ERROR:', e.message); process.exit(2); });
