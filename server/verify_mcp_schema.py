#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v0.4.5 MCP tools/list Schema 合规性自动校验脚本。

流程：
  1. 以子进程启动真实 HTTP 服务（LINJIAN_TOKEN 由脚本注入）；
  2. 走完整 MCP 握手：initialize -> notifications/initialized -> tools/list；
  3. 递归扫描响应中所有 "type":"array" 节点，统计缺失 "items" 的数量；
  4. 专项复核 7 个历史违规字段（import_timetable.entries、
     write_diary_entry.tags、search_diary_entries.tags、update_diary_entry.tags、
     set_care_policy.allowed_actions、set_tour_schedule.exempt_packages、
     run_sequence.steps）；
  5. MISSING_ITEMS_COUNT == 0 时输出 PERFECT: ZERO_ERRORS_ALL_RESOLVED（退出码 0），
     否则输出违规明细并以退出码 1 失败。

用法：
  python3 verify_mcp_schema.py            # 起真实服务做端到端校验（默认）
  python3 verify_mcp_schema.py --static   # 仅静态校验 mcp_tools.json / 引擎出口
"""
import json
import os
import secrets
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS_JSON = HERE / "mcp_tools.json"
SERVER_PY = HERE / "linjian_server.py"

# 用户点名的 7 个历史违规字段：(tool, field)
TARGETS = [
    ("import_timetable", "entries"),
    ("write_diary_entry", "tags"),
    ("search_diary_entries", "tags"),
    ("update_diary_entry", "tags"),
    ("set_care_policy", "allowed_actions"),
    ("set_tour_schedule", "exempt_packages"),
    ("run_sequence", "steps"),
]


def count_missing_items(node, path="$"):
    """递归统计 type==array 且缺 items 的节点，返回 (count, [违规路径])。"""
    bad = []
    n = 0
    if isinstance(node, dict):
        if node.get("type") == "array" and "items" not in node:
            n += 1
            bad.append(path)
        for k, v in node.items():
            c, b = count_missing_items(v, "%s.%s" % (path, k))
            n += c
            bad.extend(b)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            c, b = count_missing_items(v, "%s[%d]" % (path, i))
            n += c
            bad.extend(b)
    return n, bad


def check_targets(tools, label):
    """专检 7 个字段在指定来源（静态 JSON 或 tools/list 响应）中的 items 状态。"""
    index = {}
    for t in tools:
        if isinstance(t, dict) and t.get("name"):
            index[t["name"]] = t
    print("---- 7 个历史违规字段专项复核（%s）----" % label)
    ok = True
    for name, field in TARGETS:
        t = index.get(name)
        if t is None:
            print("  [FAIL] %s: 工具不存在" % name)
            ok = False
            continue
        schema = t.get("inputSchema") or {}
        props = schema.get("properties") or {}
        node = props.get(field)
        if not isinstance(node, dict) or node.get("type") != "array":
            print("  [FAIL] %s.%s: 字段缺失或不是 array" % (name, field))
            ok = False
        elif "items" not in node:
            print("  [FAIL] %s.%s: array 缺 items" % (name, field))
            ok = False
        else:
            it = node["items"]
            it_type = it.get("type") if isinstance(it, dict) else None
            print("  [OK]   %s.%s -> items.type=%s" % (name, field, it_type))
    return ok


def http_post(url, payload, timeout=15):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data,
                                 headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=timeout)


def rpc(method, params=None, _id=None):
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if _id is not None:
        msg["id"] = _id
    return msg


def wait_ready(proc, url, deadline_s=30):
    t0 = time.time()
    while time.time() - t0 < deadline_s:
        if proc.poll() is not None:
            raise RuntimeError("服务进程提前退出，退出码=%s" % proc.returncode)
        try:
            r = http_post(url, rpc("ping", _id=0), timeout=3)
            r.read()
            return True
        except Exception:
            time.sleep(0.4)
    return False


def verify_static():
    """静态校验：mcp_tools.json 原始定义 + 引擎 tools_list() 出口。"""
    print("== 静态校验 mcp_tools.json ==")
    raw = json.loads(TOOLS_JSON.read_text(encoding="utf-8"))
    n, bad = count_missing_items(raw)
    ok1 = check_targets(raw, "mcp_tools.json 原始定义")
    print("MISSING_ITEMS_COUNT(raw json) = %d" % n)

    print("== 静态校验引擎 tools_list() 出口 ==")
    sys.path.insert(0, str(HERE))
    import mcp_engine
    class _NullCtx:  # tools_list() 不触碰 ctx，仅 LocalState 需要 data_dir
        data_dir = str(HERE / ".verify_tmp")
    engine = mcp_engine.McpEngine(ctx=_NullCtx(), tools=raw)
    out = engine.tools_list()
    n2, bad2 = count_missing_items(out)
    ok2 = check_targets(out, "tools_list() 出口")
    print("MISSING_ITEMS_COUNT(tools_list) = %d" % n2)
    if bad2:
        for b in bad2:
            print("  MISSING ->", b)

    passed = ok1 and ok2 and n == 0 and n2 == 0
    report(passed, n, n2)
    return 0 if passed else 1


def verify_e2e():
    """端到端校验：起真实服务 -> MCP 握手 -> tools/list -> 递归扫描。"""
    port = int(os.environ.get("VERIFY_PORT", "0") or 0) or 18599
    token = "verify-" + secrets.token_hex(16)
    env = dict(os.environ)
    env["LINJIAN_TOKEN"] = token
    env["LINJIAN_PORT"] = str(port)
    print("== 端到端校验：启动服务 @127.0.0.1:%d ==" % port)
    proc = subprocess.Popen([sys.executable, str(SERVER_PY), "--port", str(port)],
                            cwd=str(HERE), env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    url = "http://127.0.0.1:%d/mcp" % port
    resp = None
    try:
        if not wait_ready(proc, url):
            raise RuntimeError("服务在 30s 内未就绪")
        # 1) initialize
        r = http_post(url, rpc("initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "schema-verifier", "version": "1.0"},
        }, _id=1))
        init = json.loads(r.read().decode("utf-8"))
        server_info = (init.get("result") or {}).get("serverInfo") or {}
        print("initialize OK -> serverInfo:", server_info.get("name"),
              server_info.get("version"))
        # 2) initialized 通知
        http_post(url, rpc("notifications/initialized")).read()
        # 3) tools/list
        r = http_post(url, rpc("tools/list", {}, _id=2))
        resp = json.loads(r.read().decode("utf-8"))
        tools = (resp.get("result") or {}).get("tools") or []
        print("tools/list OK -> 工具数=%d" % len(tools))
        if not tools:
            raise RuntimeError("tools/list 返回空工具表")

        n, bad = count_missing_items(resp)
        print("MISSING_ITEMS_COUNT(tools/list 响应全文) = %d" % n)
        for b in bad:
            print("  MISSING ->", b)
        ok_targets = check_targets(tools, "tools/list 响应")

        passed = (n == 0) and ok_targets
        report(passed, n, 0)
        return 0 if passed else 1
    finally:
        if proc.poll() is None:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=5)
            except Exception:
                proc.kill()
        try:
            out = proc.stdout.read().decode("utf-8", "replace")
            tail = "\n".join(out.strip().splitlines()[-8:])
            if tail:
                print("---- 服务日志（尾部 8 行）----")
                print(tail)
        except Exception:
            pass


def report(passed, count_raw, count_out):
    print("=" * 60)
    if passed:
        print("MISSING_ITEMS_COUNT = 0")
        print("PERFECT: ZERO_ERRORS_ALL_RESOLVED")
    else:
        print("MISSING_ITEMS_COUNT = %d（仍存在违规，禁止上线）" % (count_raw + count_out))
        print("FAILED: SCHEMA_ERRORS_REMAIN")
    print("=" * 60)


def main():
    argv = sys.argv[1:]
    if "--static" in argv:
        sys.exit(verify_static())
    sys.exit(verify_e2e())


if __name__ == "__main__":
    main()
