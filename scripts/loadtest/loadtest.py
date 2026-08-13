#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DevMind 压测基线脚本（P2 性能专项，纯标准库，无第三方依赖）。

覆盖两个核心场景：
  1. chat    — RAG 问答：POST /api/knowledge-bases/{kb-id}/chat（文档检索 + LLM 生成）
  2. workflow — 工作流执行：POST /api/workflows/{id}/run（同步多步骤编排）

输出：总请求 / 成功率 / QPS / 平均延迟 / P50 / P95 / P99 / Max。

用法示例：
  python loadtest.py --endpoint chat --kb-id 1 --concurrency 8 --duration 30
  python loadtest.py --endpoint workflow --workflow-id 1 --concurrency 4 --duration 30
  python loadtest.py --endpoint chat --kb-id 1 --base-url http://localhost:8090 \
      --headers '{"X-User-Id":"1"}' --concurrency 16 --duration 60 --report report.md

说明：
  - 默认 X-User-Id=1（管理员/免登录），可通过 --headers 覆盖（如 Bearer token）。
  - 安全：RAG 问答（chat）是端到端场景，会调用 AI 模型（有成本）。默认拒绝执行，
    须显式传 --confirm-mock-model 确认当前服务为 mock 模式（不调用真实 AI）后才会运行。
    注意：即使 --spring.profiles.active=mock，环境变量（如 .env 的 DEVMIND_MODEL_MODE）
    可能覆盖 model-mode 回到真实模型——压测前务必确认服务日志/model 配置。
  - mock 模式（model-mode=mock）下 LLM 为确定性伪实现，测得的是"平台链路 + 基础设施"
    基线；真实模型模式测得的是"含模型延迟"的端到端基线——后者主要测的是 AI 供应商，
    对平台自身的性能评估价值有限。
"""
import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request


def log(msg):
    print(msg, flush=True)


def do_request(url, payload, headers, timeout):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            ok = 200 <= resp.status < 300
            return ok, time.perf_counter() - start, resp.status, None
    except urllib.error.HTTPError as e:
        return False, time.perf_counter() - start, e.code, e.read()[:200].decode("utf-8", "ignore")
    except Exception as e:
        return False, time.perf_counter() - start, None, str(e)[:200]


def worker(args, urls, payloads, headers, stop_event, results, errors):
    idx = 0
    while not stop_event.is_set():
        url = urls[idx % len(urls)]
        payload = payloads[idx % len(payloads)] if payloads else {}
        idx += 1
        ok, latency, status, err = do_request(url, payload, headers, args.timeout)
        results.append((ok, latency * 1000.0))
        if not ok:
            errors.append((status, err))
            if args.stop_on_error:
                log(f"  错误 status={status} err={err}")
                stop_event.set()


def percentiles(latencies_ms):
    if not latencies_ms:
        return 0.0, 0.0, 0.0, 0.0, 0.0
    latencies_ms.sort()
    n = len(latencies_ms)
    p = lambda q: latencies_ms[min(n - 1, int(q * n))]

    def pct(q):
        idx = int(q * (n - 1))
        return round(latencies_ms[idx], 1)

    return (
        round(statistics.mean(latencies_ms), 1),
        pct(0.5),
        pct(0.95),
        pct(0.99),
        round(latencies_ms[-1], 1),
    )


def main():
    parser = argparse.ArgumentParser(description="DevMind 压测基线（P2）")
    parser.add_argument("--endpoint", choices=["chat", "workflow", "search"], required=True,
                        help="chat=RAG 问答 | workflow=工作流执行 | search=纯语义检索（不调 LLM）")
    parser.add_argument("--kb-id", type=int, help="知识库 ID（endpoint=chat/search 必填）")
    parser.add_argument("--workflow-id", help="工作流 ID（endpoint=workflow 必填；逗号分隔可传多个，轮流压测）")
    parser.add_argument("--question", default="深分页为什么慢",
                        help="测试问题（endpoint=chat/search，默认：深分页为什么慢）")
    parser.add_argument("--base-url", default="http://localhost:8090")
    parser.add_argument("--headers", default='{"X-User-Id":"1"}',
                        help="额外请求头（JSON），默认 X-User-Id=1")
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--duration", type=int, default=30, help="压测时长（秒）")
    parser.add_argument("--timeout", type=float, default=30.0, help="单请求超时（秒）")
    parser.add_argument("--stop-on-error", action="store_true", help="首个失败即停止")
    parser.add_argument("--confirm-mock-model", action="store_true",
                        help="确认服务为 mock 模式（不调用真实 AI）后放行 chat 场景；缺省拒绝")
    parser.add_argument("--report", help="输出 Markdown 报告文件路径（可选）")
    args = parser.parse_args()

    if args.endpoint in ("chat", "search"):
        # 安全守卫：chat 调用完整 AI 链路（生成有成本）；search 也调用 embedding（mock 守卫一致，
        # 防止真实 embedding 供应商被打）。除非显式确认当前是 mock 模式，否则拒绝执行。
        if not args.confirm_mock_model:
            log("【安全拦截】chat/search 场景会调用 AI（embedding）产生费用。\n"
                "如确认当前服务为 mock 模式（model-mode=mock，不调用真实 AI），请加 --confirm-mock-model 重试。\n"
                "注意：仅 spring.profiles.active=mock 不够——.env 等环境变量可能覆盖 model-mode。")
            sys.exit(1)
        if not args.kb_id:
            log("endpoint=chat/search 需要 --kb-id")
            sys.exit(1)
        path = "chat" if args.endpoint == "chat" else "search"
        urls = [f"{args.base_url}/api/knowledge-bases/{args.kb_id}/{path}"]
        payloads = [{"question": args.question, "topK": 3}]
        scenario = (f"RAG 问答（KB={args.kb_id}）" if args.endpoint == "chat"
                    else f"纯语义检索（KB={args.kb_id}，不调 LLM）")
    else:
        if not args.workflow_id:
            log("endpoint=workflow 需要 --workflow-id")
            sys.exit(1)
        # 同一工作流同步执行默认单实例互斥（防重叠副作用），
        # 多工作流轮流压测可测编排引擎的并发吞吐上限。
        workflow_ids = [w.strip() for w in args.workflow_id.split(",") if w.strip()]
        urls = [f"{args.base_url}/api/workflows/{w}/run" for w in workflow_ids]
        payloads = []
        scenario = f"工作流执行（ids={'/'.join(workflow_ids)}，{len(workflow_ids)} 个轮流）"

    try:
        headers = json.loads(args.headers)
        headers.setdefault("Content-Type", "application/json")
    except json.JSONDecodeError:
        log("--headers 不是合法 JSON")
        sys.exit(1)

    # 预热：确认端点连通
    log(f"[预热] {urls[0]}")
    ok, latency, status, err = do_request(urls[0], (payloads[0] if payloads else {}), headers, args.timeout)
    if not ok:
        log(f"[预热失败] status={status} err={err}\n请确认服务已启动、数据已准备。")
        sys.exit(1)
    log(f"[预热成功] {latency * 1000:.0f} ms\n")

    # 并发压测
    stop_event = threading.Event()
    results = []
    errors = []
    threads = []
    start = time.perf_counter()
    for _ in range(args.concurrency):
        t = threading.Thread(target=worker, args=(args, urls, payloads, headers, stop_event, results, errors))
        t.daemon = True
        t.start()
        threads.append(t)

    time.sleep(args.duration)
    stop_event.set()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - start

    total = len(results)
    ok_count = sum(1 for ok, _ in results if ok)
    success_rate = ok_count / total * 100 if total else 0.0
    qps = total / elapsed if elapsed > 0 else 0.0
    mean, p50, p95, p99, mx = percentiles([lat for _, lat in results])

    lines = [
        f"# DevMind 压测基线",
        f"",
        f"- 场景：**{scenario}**",
        f"- 地址：`{args.base_url}` 并发：{args.concurrency} 时长：{args.duration}s",
        f"- 总请求：{total} 成功：{ok_count} 失败：{total - ok_count}",
        f"- 成功率：**{success_rate:.1f}%**",
        f"- QPS：**{qps:.1f}**",
        f"- 延迟（ms）：平均 {mean} | P50 {p50} | P95 {p95} | P99 {p99} | Max {mx}",
    ]
    report = "\n".join(lines)
    log("\n=== DevMind 压测基线 ===")
    log(f"场景: {scenario}  并发: {args.concurrency}  时长: {args.duration}s")
    log(f"总请求: {total}  成功: {ok_count}  失败: {total - ok_count}  成功率: {success_rate:.1f}%")
    log(f"QPS: {qps:.1f}")
    log(f"延迟(ms): 平均 {mean} | P50 {p50} | P95 {p95} | P99 {p99} | Max {mx}")
    if errors:
        log(f"\n[错误样本] 前 5 条:")
        for status, err in errors[:5]:
            log(f"  status={status} err={err}")

    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write(report + "\n")
        log(f"\n报告已写入: {args.report}")

    sys.exit(0 if success_rate >= 99 else 1)


if __name__ == "__main__":
    main()
