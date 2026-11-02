#!/usr/bin/env python3
"""掌心窗公开版 v0.3.9.0 unified server.

零依赖标准库版，负责：
1. 给手机端下发 peek / open_app / back / home / recents / tap / swipe / set_alarm / send_notification 命令；
2. 接收手机端上传的截图；
3. 保存手机端最近状态；
4. 提供 /api/latest 与 /api/latest.json 给 MCP 读取。
"""
from __future__ import annotations

import calendar
import json
import os
import subprocess
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, unquote, urlparse
from urllib.request import Request, urlopen

DEFAULT_PORT = 8513
DEFAULT_KEEP = 3
MAX_UPLOAD_BYTES = 24 * 1024 * 1024
VERSION = "0.3.9.0"
DEFAULT_DEVICE = os.environ.get("LINJIAN_DEFAULT_DEVICE", "android-phone")
ACTIVITY_EVENT_LIMIT = 500

ERR_BAD_TOKEN = "LINJIAN_ERR_BAD_TOKEN"
ERR_NO_IMAGE = "LINJIAN_ERR_NO_IMAGE"
ERR_TOO_LARGE = "LINJIAN_ERR_TOO_LARGE"
ERR_NOT_FOUND = "LINJIAN_ERR_NOT_FOUND"
ERR_BAD_METHOD = "LINJIAN_ERR_BAD_METHOD"

# 命令卡死回收：手机端取走命令后若未及时回执（status 停在 dispatched），
# 超过该秒数就重置为 pending 重新下发，避免“显示已连接却不再取命令”。
COMMAND_STUCK_SECONDS = int(os.environ.get("LINJIAN_COMMAND_STUCK_SECONDS", "30"))

KNOWN_APPS = {
    "小红书": "com.xingin.xhs", "xhs": "com.xingin.xhs", "xiaohongshu": "com.xingin.xhs",
    "微信": "com.tencent.mm", "wechat": "com.tencent.mm",
    "QQ": "com.tencent.mobileqq", "qq": "com.tencent.mobileqq",
    "抖音": "com.ss.android.ugc.aweme", "douyin": "com.ss.android.ugc.aweme",
    "Speedcat": "", "speedcat": "",
    "美团": "com.sankuai.meituan", "meituan": "com.sankuai.meituan",
    "饿了么": "me.ele", "eleme": "me.ele",
    "京东": "com.jingdong.app.mall", "jd": "com.jingdong.app.mall",
}
SENSITIVE_PACKAGES = {"com.eg.android.AlipayGphone", "com.tencent.mm.plugin.wallet"}
ALLOWED_ACTIONS = {"noop", "peek", "open_app", "home", "back", "recents", "screen_off", "turn_screen_off", "lock_screen", "phone_screen_off", "tap", "swipe", "set_alarm", "send_notification", "run_sequence", "save_known_app", "get_screen_nodes", "tap_text", "input_text", "lock_app", "unlock_app", "temporary_unlock_app", "extend_lock", "deny_unlock_request", "get_lock_state", "set_emergency_passphrase", "add_locked_app", "remove_locked_app", "list_lockable_apps", "screen_break_app", "start_screen_break", "screen_break", "end_screen_break", "stop_screen_break", "temporary_screen_break_release", "temporary_screen_release", "extend_screen_break", "deny_screen_break_release_request", "deny_break_release_request", "get_screen_break_state", "set_screen_break_passphrase", "add_screen_break_app", "remove_screen_break_app", "list_screen_break_apps", "get_focus_status", "get_life_state", "start_focus_mode", "end_focus_mode", "set_focus_plan", "reply_focus_request", "approve_focus_unlock", "deny_focus_unlock", "request_focus_unlock", "create_focus_request", "get_guidian_state", "set_guidian_config", "trigger_guidian", "mark_guidian_returned", "get_calendar_state", "upsert_calendar_event", "add_calendar_event", "delete_calendar_event", "create_diary_book", "list_diary_books", "rename_diary_book", "update_diary_book_cover", "write_diary_entry", "list_diary_entries", "read_diary_entry", "read_diary_entry_with_annotations", "add_diary_annotation", "list_diary_annotations", "mark_diary_annotations_seen", "delete_diary_annotation", "search_diary_entries", "update_diary_entry", "delete_diary_entry", "delete_diary_book",
"show_center_alert", "force_back_to_chat", "start_tour", "stop_tour"}

def load_dotenv(path: Path) -> None:
    if not path.exists(): return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line: continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def parse_iso_seconds(value: str) -> float:
    try: return calendar.timegm(time.strptime(str(value or ""), "%Y-%m-%dT%H:%M:%SZ"))
    except Exception: return 0.0


def activity_type_for_action(action: str) -> str:
    value = str(action or "")
    if value.startswith("get_") or value.startswith("list_") or value in ("peek", "get_screen_nodes"):
        return "status_check"
    if "calendar" in value: return "calendar"
    if "weather" in value: return "weather"
    if "notification" in value: return "notification"
    return "command"


class State:
    def __init__(self) -> None:
        here = Path(__file__).resolve().parent
        load_dotenv(here / ".env")
        self.token = os.environ.get("LINJIAN_TOKEN", "").strip()
        self.port = int(os.environ.get("PORT", os.environ.get("LINJIAN_PORT", DEFAULT_PORT)))
        self.host = os.environ.get("LINJIAN_HOST", "0.0.0.0")
        self.keep = int(os.environ.get("LINJIAN_KEEP", DEFAULT_KEEP))
        self.hook = os.environ.get("LINJIAN_HOOK", "").strip()
        data_dir = os.environ.get("LINJIAN_DATA_DIR", str(here / "data"))
        self.data_dir = Path(data_dir).resolve()
        self.shots_dir = self.data_dir / "screenshots"
        self.shots_dir.mkdir(parents=True, exist_ok=True)
        self.commands: list[dict] = []
        self.command_history: dict[str, dict] = {}
        self.commands_lock = Lock()
        self.device_states: dict[str, dict] = {}
        self.unlock_requests: list[dict] = []
        self.companion_path = self.data_dir / "companion_state.json"
        self.companion_lock = Lock()
        self.companion_state = self._load_companion_state()
        self.activity_path = self.data_dir / "activity_events.json"
        self.activity_lock = Lock()
        self.activity_events = self._load_activity_events()
        # 主动关心：巡回查岗 / 居中弹窗 / 强制拉回 / 情绪感知
        self.tour_path = self.data_dir / "tour_schedule.json"
        self.tour_schedule = self._load_tour_schedule()
        self.event_log_path = self.data_dir / "tour_events.json"
        self.tour_lock = Lock()
        self.event_log = self._load_event_log()
        self.emotion_path = self.data_dir / "emotion_state.json"
        self.emotion_state = self._load_emotion_state()
        self.usage_sessions: dict[str, dict] = {}
        self._stop = False

    def _load_activity_events(self) -> list[dict]:
        try:
            if self.activity_path.exists():
                loaded = json.loads(self.activity_path.read_text(encoding="utf-8"))
                if isinstance(loaded, list): return loaded[:ACTIVITY_EVENT_LIMIT]
        except Exception:
            pass
        return []

    def save_activity_events(self) -> None:
        self.activity_path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.activity_path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.activity_events[:ACTIVITY_EVENT_LIMIT], ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(self.activity_path)

    def _load_tour_schedule(self) -> dict:
        try:
            if self.tour_path.exists():
                loaded = json.loads(self.tour_path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    merged = dict(DEFAULT_TOUR_SCHEDULE)
                    merged.update(loaded)
                    return merged
        except Exception:
            pass
        return dict(DEFAULT_TOUR_SCHEDULE)

    def save_tour_schedule(self) -> None:
        self.tour_path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.tour_path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.tour_schedule, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(self.tour_path)

    def _load_event_log(self) -> list[dict]:
        try:
            if self.event_log_path.exists():
                loaded = json.loads(self.event_log_path.read_text(encoding="utf-8"))
                if isinstance(loaded, list): return loaded[-200:]
        except Exception:
            pass
        return []

    def _load_emotion_state(self) -> dict:
        try:
            if self.emotion_path.exists():
                loaded = json.loads(self.emotion_path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict): return loaded
        except Exception:
            pass
        return {}

    def add_activity_event(self, data: dict, dedupe_seconds: int = 0) -> dict:
        with self.activity_lock:
            event_id = clip_text(str(data.get("id") or ""), 100) or str(uuid.uuid4())
            previous = next((e for e in self.activity_events if e.get("id") == event_id), None)
            metadata = data.get("metadata_json", data.get("metadata", {}))
            if isinstance(metadata, str):
                try: metadata = json.loads(metadata)
                except Exception: metadata = {"value": clip_text(metadata, 1000)}
            if not isinstance(metadata, (dict, list)): metadata = {}
            entry = {
                "id": event_id,
                "device_id": clip_text(str(data.get("device_id") or DEFAULT_DEVICE), 80),
                "created_at": clip_text(str(data.get("created_at") or data.get("at") or now_iso()), 40),
                "source": clip_text(str(data.get("source") or "companion"), 24),
                "type": clip_text(str(data.get("type") or "activity"), 40),
                "title": clip_text(str(data.get("title") or ""), 100),
                "subtitle": clip_text(str(data.get("subtitle") or data.get("summary") or ""), 220),
                "app_name": clip_text(str(data.get("app_name") or data.get("app") or ""), 80),
                "package_name": clip_text(str(data.get("package_name") or data.get("package") or ""), 180),
                "action": clip_text(str(data.get("action") or ""), 80),
                "status": clip_text(str(data.get("status") or "completed"), 24),
                "metadata_json": metadata,
            }
            if previous is not None:
                if not data.get("created_at") and not data.get("at"):
                    entry["created_at"] = previous.get("created_at") or entry["created_at"]
                previous.update({k: v for k, v in entry.items() if v not in ("", None) or k in ("status", "metadata_json")})
                self.save_activity_events()
                return dict(previous)
            if dedupe_seconds > 0:
                now = time.time()
                duplicate = next((e for e in self.activity_events[:40]
                    if e.get("device_id") == entry["device_id"] and e.get("source") == entry["source"]
                    and e.get("type") == entry["type"] and e.get("action") == entry["action"]
                    and e.get("package_name") == entry["package_name"]
                    and now - parse_iso_seconds(e.get("created_at")) <= dedupe_seconds), None)
                if duplicate is not None: return dict(duplicate)
            self.activity_events.insert(0, entry)
            del self.activity_events[ACTIVITY_EVENT_LIMIT:]
            self.save_activity_events()
            return dict(entry)

    def list_activity_events(self, device_id: str = "", date: str = "", source: str = "", limit: int = 50) -> list[dict]:
        with self.activity_lock:
            items = list(self.activity_events)
        if device_id: items = [e for e in items if e.get("device_id") == device_id]
        if date: items = [e for e in items if str(e.get("created_at") or "").startswith(date)]
        if source: items = [e for e in items if e.get("source") == source]
        return items[:max(1, min(500, limit))]

    def _load_companion_state(self) -> dict:
        fallback = {
            "whisper": {
                "content": "把今天，轻轻收进窗里。",
                "author": "陪伴对象",
                "updated_at": now_iso(),
                "version": 1,
            },
            "actions": [],
        }
        try:
            if self.companion_path.exists():
                loaded = json.loads(self.companion_path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    fallback.update(loaded)
        except Exception:
            pass
        return fallback

    def save_companion_state(self) -> None:
        self.companion_path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.companion_path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.companion_state, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(self.companion_path)

    def update_whisper(self, content: str, author: str) -> dict:
        with self.companion_lock:
            previous = self.companion_state.get("whisper") or {}
            whisper = {
                "content": clip_text(content, 240),
                "author": clip_text(author, 24) or "用户",
                "updated_at": now_iso(),
                "version": int(previous.get("version") or 0) + 1,
            }
            self.companion_state["whisper"] = whisper
            self.save_companion_state()
            return whisper

    def record_companion_action(self, data: dict) -> dict:
        with self.companion_lock:
            entry = {
                "id": str(uuid.uuid4()),
                "at": now_iso(),
                "kind": clip_text(str(data.get("kind") or "陪伴"), 24),
                "title": clip_text(str(data.get("title") or "完成了一次行动"), 80),
                "summary": clip_text(str(data.get("summary") or ""), 160),
                "status": clip_text(str(data.get("status") or "completed"), 24),
                "source": "companion",
            }
            actions = self.companion_state.setdefault("actions", [])
            actions.insert(0, entry)
            del actions[120:]
            self.save_companion_state()
        if data.get("write_activity", True):
            self.add_activity_event({
                "id": data.get("activity_id") or data.get("id") or "",
                "device_id": data.get("device_id") or DEFAULT_DEVICE, "source": "companion",
                "type": data.get("type") or "activity", "title": entry["title"],
                "subtitle": entry["summary"], "action": data.get("action") or "",
                "status": entry["status"], "metadata_json": data.get("metadata_json") or {}
            }, int(data.get("dedupe_seconds") or 0))
        return entry

    def latest_shot(self) -> Path | None:
        shots = sorted(self.shots_dir.glob("peek_*"), key=lambda p: p.stat().st_mtime)
        return shots[-1] if shots else None


def package_for(app_name: str, package: str = "") -> str:
    pkg = (package or "").strip()
    if pkg: return pkg
    key = (app_name or "").strip()
    return KNOWN_APPS.get(key, KNOWN_APPS.get(key.lower(), ""))


def make_command(device_id: str, action: str, app: str = "", package: str = "", payload: dict | None = None) -> dict:
    action = (action or "noop").strip().lower()
    if action not in ALLOWED_ACTIONS: action = "noop"
    package = package_for(app, package)
    payload = dict(payload or {})
    if package in SENSITIVE_PACKAGES:
        action = "noop"
        payload["blocked_reason"] = "sensitive_package"
    cmd = {
        "id": str(uuid.uuid4()),
        "device_id": device_id or DEFAULT_DEVICE,
        "action": action,
        "app": app or "",
        "package": package or "",
        "payload": payload,
        "status": "pending",
        "created_at": now_iso(),
        "dispatched_at": None,
        "completed_at": None,
        "result": "",
    }
    cmd.update(payload)
    return cmd


def clip_text(value: str, limit: int = 1200) -> str:
    value = (value or "").strip()
    if len(value) <= limit:
        return value
    return value[:limit].rstrip() + "…"


class Handler(BaseHTTPRequestHandler):
    state: State

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[linjian-unified] %s - %s\n" % (self.address_string(), fmt % args))

    def _send_bytes(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, payload: dict) -> None:
        self._send_bytes(code, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

    def _token_ok(self) -> bool:
        qs = parse_qs(urlparse(self.path).query)
        supplied = self.headers.get("X-Auth-Token", "") or qs.get("token", [""])[0]
        return bool(self.state.token) and supplied == self.state.token

    def _require_token(self) -> bool:
        if self._token_ok(): return True
        self._json(403, {"ok": False, "error": ERR_BAD_TOKEN}); return False

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length <= 0: return {}
        raw = self.rfile.read(length)
        try: return json.loads(raw.decode("utf-8"))
        except Exception: return {}


    def _public_base(self) -> str:
        configured = os.environ.get("LINJIAN_PUBLIC_URL", "").strip().rstrip("/")
        if configured:
            return configured
        proto = self.headers.get("X-Forwarded-Proto", "https") or "https"
        host = self.headers.get("X-Forwarded-Host") or self.headers.get("Host") or f"localhost:{self.state.port}"
        return f"{proto}://{host}".rstrip("/")

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)
        if path in ("/", "/health"):
            self._json(200, {"ok": True, "service": "linjian-public", "name": "掌心窗", "version": VERSION, "tools": sorted(ALLOWED_ACTIONS), "guidian": True, "calendar": True, "diary": True, "diary_storage": "phone_local", "app_gate": True, "focus_tools": True, "diary_rename_fix": True, "diary_write_fallback": True, "diary_annotation_tools": True, "diary_annotation_whitelist_fix": True})
            return
        if path in ("/mcp", "/sse"):
            self._json(400, {"ok": False, "error": "LINJIAN_ERR_WRONG_SERVICE", "message": "你访问的是掌心窗 server 服务，不是 MCP 服务。请单独部署 mcp 目录，并在 MCP 客户端填写 MCP 服务域名 + /mcp 或 /sse。"})
            return
        if path == "/api/companion/state":
            if not self._require_token(): return
            limit = max(1, min(50, int(qs.get("limit", ["20"])[0] or 20)))
            with self.state.companion_lock:
                whisper = dict(self.state.companion_state.get("whisper") or {})
                actions = list(self.state.companion_state.get("actions") or [])[:limit]
            unified = [e for e in self.state.list_activity_events("", "", "", ACTIVITY_EVENT_LIMIT)
                       if e.get("source") in ("companion", "assistant") or e.get("type") in ("command", "notification", "weather", "calendar", "status_check")][:limit]
            if unified:
                actions = [{"id": e.get("id"), "at": e.get("created_at"), "created_at": e.get("created_at"), "kind": e.get("type"), "type": e.get("type"), "title": e.get("title"), "summary": e.get("subtitle"), "subtitle": e.get("subtitle"), "status": e.get("status"), "source": "companion"} for e in unified]
            self._json(200, {"ok": True, "whisper": whisper, "actions": actions}); return
        if path == "/api/activity/events":
            if not self._require_token(): return
            device_id = qs.get("device_id", [""])[0]
            date = qs.get("date", [""])[0]
            source = qs.get("source", [""])[0]
            limit = max(1, min(500, int(qs.get("limit", ["50"])[0] or 50)))
            events = self.state.list_activity_events(device_id, date, source, limit)
            self._json(200, {"ok": True, "events": events, "count": len(events)}); return
        if path == "/api/poll":
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            with self.state.commands_lock:
                # 回收：手机已取走但超过 COMMAND_STUCK_SECONDS 仍未回执的命令，重置为 pending 重新下发，
                # 修复“App 显示已连接但其实不再取命令 / 命令一直卡在 pending”的问题。
                now_ts = time.time()
                for cid, c in list(self.state.command_history.items()):
                    if c.get("device_id") == device_id and c.get("status") == "dispatched":
                        disp = parse_iso_seconds(c.get("dispatched_at") or "")
                        if disp and now_ts - disp > COMMAND_STUCK_SECONDS:
                            c["status"] = "pending"
                            c.pop("dispatched_at", None)
                            self.state.commands.append(c)
                idx = next((i for i, c in enumerate(self.state.commands) if c.get("device_id") == device_id and c.get("status") == "pending"), None)
                if idx is None:
                    self._json(200, {"ok": True, "command": None}); return
                cmd = self.state.commands.pop(idx)
                cmd["status"] = "dispatched"; cmd["dispatched_at"] = now_iso()
                self.state.command_history[cmd.get("id", "")] = dict(cmd)
            self._json(200, {"ok": True, "command": cmd})
            return
        if path == "/api/latest.json":
            if not self._require_token(): return
            shot = self.state.latest_shot()
            if not shot: self._json(404, {"ok": False, "error": ERR_NOT_FOUND}); return
            st = shot.stat(); self._json(200, {"ok": True, "filename": shot.name, "size": st.st_size, "mtime": st.st_mtime, "url": "/api/latest"}); return
        if path == "/api/latest":
            if not self._require_token(): return
            shot = self.state.latest_shot()
            if not shot: self._json(404, {"ok": False, "error": ERR_NOT_FOUND}); return
            ctype = "image/png" if shot.suffix.lower() == ".png" else "image/jpeg"
            self._send_bytes(200, shot.read_bytes(), ctype); return
        if path in ("/api/device/state", "/api/life_state"):
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            state = self.state.device_states.get(device_id)
            self._json(200, {"ok": True, "device_id": device_id, "state": state, "life_state": state}); return
        if path == "/api/guidian_state":
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            st = self.state.device_states.get(device_id) or {}
            guidian = st.get("guidian_state") or {}
            self._json(200, {"ok": True, "device_id": device_id, "guidian_state": guidian}); return
        if path == "/api/command/status":
            if not self._require_token(): return
            cid = qs.get("id", [""])[0]
            with self.state.commands_lock:
                found = self.state.command_history.get(cid) or next((c for c in self.state.commands if c.get("id") == cid), None)
            self._json(200, {"ok": bool(found), "command": found}); return
        if path == "/api/appgate/unlock_requests":
            if not self._require_token(): return
            self._json(200, {"ok": True, "requests": self.state.unlock_requests[-50:]}); return
        if path == "/api/known_apps":
            self._json(200, {"ok": True, "apps": KNOWN_APPS}); return
        if path == "/api/usage_snapshot":
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            self._json(200, build_usage_snapshot(self.state, device_id)); return
        if path == "/api/emotion/snapshot":
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            self._json(200, build_emotion_snapshot(self.state, device_id)); return
        if path == "/api/tour/state":
            if not self._require_token(): return
            with self.state.tour_lock:
                events = list(self.state.event_log[-20:])
            self._json(200, {"ok": True, "schedule": load_tour_schedule(self.state), "recent_events": events,
                             "usage_sessions": {k: (v.get("package") if v else "") for k, v in self.state.usage_sessions.items()}}); return
        self._json(404, {"ok": False, "error": ERR_BAD_METHOD})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path in ("/mcp", "/sse"):
            self._json(400, {"ok": False, "error": "LINJIAN_ERR_WRONG_SERVICE", "message": "你访问的是掌心窗 server 服务，不是 MCP 服务。请单独部署 mcp 目录，并在 MCP 客户端填写 MCP 服务域名 + /mcp 或 /sse。"})
            return
        if path == "/api/companion/whisper":
            if not self._require_token(): return
            data = self._read_json()
            content = (data.get("content") or "").strip()
            if not content:
                self._json(400, {"ok": False, "error": "content_required"}); return
            whisper = self.state.update_whisper(content, data.get("author") or "用户")
            self._json(200, {"ok": True, "whisper": whisper}); return
        if path == "/api/companion/action":
            if not self._require_token(): return
            entry = self.state.record_companion_action(self._read_json())
            self._json(200, {"ok": True, "action": entry}); return
        if path == "/api/activity/events":
            if not self._require_token(): return
            data = self._read_json()
            event = self.state.add_activity_event(data, max(0, min(300, int(data.get("dedupe_seconds") or 0))))
            self._json(200, {"ok": True, "event": event}); return
        if path == "/api/peek":
            if not self._require_token(): return
            self._queue(make_command(DEFAULT_DEVICE, "peek")); self._json(200, {"ok": True, "queued": True}); return
        if path == "/api/command":
            if not self._require_token(): return
            data = self._read_json()
            cmd = make_command(data.get("device_id") or DEFAULT_DEVICE, data.get("action") or "noop", data.get("app") or "", data.get("package") or "", data.get("payload") or data)
            action = cmd.get("action") or "noop"
            app_name = cmd.get("app") or (cmd.get("payload") or {}).get("app") or ""
            title_map = {
                "send_notification": "发送提醒", "set_alarm": "设置闹钟", "trigger_guidian": "发起归电",
                "mark_guidian_returned": "记录归电回应", "get_guidian_state": "查看归电状态", "set_guidian_config": "调整归电设置",
                "screen_off": "让手机息屏", "phone_screen_off": "让手机息屏", "open_app": "打开应用",
                "screen_break_app": "开启应用门禁", "lock_app": "开启应用门禁", "end_screen_break": "解除应用门禁", "unlock_app": "解除应用门禁", "extend_screen_break": "延长应用门禁", "extend_lock": "延长应用门禁",
                "temporary_screen_break_release": "临时解除应用门禁", "temporary_unlock_app": "临时解除应用门禁", "deny_screen_break_release_request": "拒绝门禁解除", "deny_unlock_request": "拒绝门禁解除",
                "get_screen_break_state": "查看应用门禁状态", "get_lock_state": "查看应用门禁状态", "list_screen_break_apps": "查看可管理应用", "list_lockable_apps": "查看可管理应用",
                "add_screen_break_app": "加入门禁管理", "add_locked_app": "加入门禁管理", "remove_locked_app": "移除门禁应用", "set_screen_break_passphrase": "设置门禁口令", "set_emergency_passphrase": "设置门禁口令",
                "get_focus_status": "查看专注模式", "get_life_state": "查看生活状态", "start_focus_mode": "开启专注模式", "end_focus_mode": "结束专注模式", "set_focus_plan": "设置专注规则", "reply_focus_request": "回复专注留言", "approve_focus_unlock": "批准专注应急", "deny_focus_unlock": "拒绝专注应急",
                "get_calendar_state": "查看守护日历", "upsert_calendar_event": "更新守护日历",
                                   
                                     
                "get_screen_nodes": "查看当前页面",
                "read_diary_entry_with_annotations": "读取日记批注", "add_diary_annotation": "保存页边纸条", "list_diary_annotations": "读取页边纸条", "mark_diary_annotations_seen": "标记纸条已看", "delete_diary_annotation": "删除页边纸条",
                "peek": "查看屏幕", "run_sequence": "执行组合动作", "home": "回到手机桌面", "back": "返回上一页", "recents": "打开最近任务",
                "show_center_alert": "屏幕居中弹窗", "force_back_to_chat": "强制拉回聊天", "start_tour": "开始巡回查岗", "stop_tour": "停止巡回查岗"
            }
            event = self.state.add_activity_event({
                "id": cmd.get("id"), "device_id": cmd.get("device_id"), "source": "companion", "type": activity_type_for_action(action),
                "title": title_map.get(action, "执行 " + action), "subtitle": app_name,
                "app_name": app_name, "package_name": cmd.get("package") or "", "action": action,
                "status": "pending", "metadata_json": {"command_id": cmd.get("id")}
            })
            cmd["activity_event_id"] = event.get("id")
            self._queue(cmd)
            with self.state.commands_lock:
                self.state.command_history[cmd.get("id", "")] = dict(cmd)
            self._json(200, {"ok": True, "command": cmd}); return
        if path == "/api/device/state":
            if not self._require_token(): return
            data = self._read_json(); device_id = data.get("device_id") or DEFAULT_DEVICE
            data["updated_at"] = now_iso(); self.state.device_states[device_id] = data
            update_usage_sessions(self.state, device_id, data)
            self._json(200, {"ok": True, "device_id": device_id}); return
        if path == "/api/device/report":
            if not self._require_token(): return
            data = self._read_json()
            cid = data.get("command_id") or data.get("id") or ""
            completed_cmd = None
            with self.state.commands_lock:
                cmd = self.state.command_history.get(cid)
                if cmd is not None:
                    cmd["status"] = "completed" if data.get("ok") else "failed"
                    cmd["completed_at"] = now_iso()
                    cmd["result"] = data.get("result", "")
                    cmd["report"] = data
                    completed_cmd = dict(cmd)
            if completed_cmd is not None:
                try:
                    self.state.add_activity_event({
                        "id": completed_cmd.get("activity_event_id") or cid, "device_id": completed_cmd.get("device_id") or DEFAULT_DEVICE,
                        "source": "companion", "type": activity_type_for_action(completed_cmd.get("action")), "action": completed_cmd.get("action") or "",
                        "status": completed_cmd["status"], "metadata_json": {"command_id": cid, "result": clip_text(str(data.get("result") or ""), 500)}
                    })
                except Exception: pass
            self._json(200, {"ok": True, "report": data, "command": self.state.command_history.get(cid)}); return
        if path == "/api/appgate/unlock_request":
            if not self._require_token(): return
            data = self._read_json(); data.setdefault("created_at", now_iso())
            self.state.unlock_requests.append(data)
            self.state.unlock_requests = self.state.unlock_requests[-50:]
            self._json(200, {"ok": True, "request": data, "count": len(self.state.unlock_requests)}); return
        if path == "/api/screenshot":
            if not self._require_token(): return
            self._handle_screenshot(); return
        if path == "/api/tour/schedule":
            if not self._require_token(): return
            data = self._read_json()
            sched = dict(load_tour_schedule(self.state))
            for key in ("enabled", "check_in_interval_minutes", "force_back_interval_minutes", "cooldown_minutes",
                        "single_app_lock_minutes", "single_app_lock_duration_minutes", "alert_tone"):
                if key in data:
                    sched[key] = data[key]
            qh = data.get("quiet_hours")
            if qh is None:
                sched["quiet_hours"] = None
            elif isinstance(qh, dict):
                sched["quiet_hours"] = {"start": str(qh.get("start", "23:00")),
                                        "end": str(qh.get("end", "08:00"))}
            apps = data.get("exempt_packages")
            if isinstance(apps, list):
                sched["exempt_packages"] = [str(a) for a in apps if a]
            self.state.tour_schedule = sched
            save_tour_schedule(self.state)
            add_tour_event(self.state, "schedule_updated", "更新巡回策略", DEFAULT_DEVICE)
            self._json(200, {"ok": True, "schedule": sched}); return
        if path == "/api/tour/start":
            if not self._require_token(): return
            self.state.tour_schedule = dict(load_tour_schedule(self.state)); self.state.tour_schedule["enabled"] = True
            save_tour_schedule(self.state)
            r = tour_step_check_in(self.state, self.state.tour_schedule)
            add_tour_event(self.state, "tour_start", "开启巡回查岗", DEFAULT_DEVICE)
            self._json(200, {"ok": True, "schedule": self.state.tour_schedule, "first_check_in": r}); return
        if path == "/api/tour/stop":
            if not self._require_token(): return
            self.state.tour_schedule = dict(load_tour_schedule(self.state)); self.state.tour_schedule["enabled"] = False
            save_tour_schedule(self.state)
            add_tour_event(self.state, "tour_stop", "停止巡回查岗", DEFAULT_DEVICE)
            self._json(200, {"ok": True, "schedule": self.state.tour_schedule}); return
        if path == "/api/tour/check_now":
            if not self._require_token(): return
            sched = load_tour_schedule(self.state)
            r = tour_step_check_in(self.state, sched)
            emo = build_emotion_snapshot(self.state, DEFAULT_DEVICE)
            self._json(200, {"ok": True, "check_in": r, "emotion_snapshot": emo}); return
        if path == "/api/emotion/tag":
            if not self._require_token(): return
            data = self._read_json()
            tag = str(data.get("tag") or data.get("emotion_tag") or "")
            note = str(data.get("note") or "")
            if not tag:
                self._json(400, {"ok": False, "error": "tag_required"}); return
            record_emotion_tag(self.state, tag, note)
            self._json(200, {"ok": True, "emotion_state": self.state.emotion_state}); return
        # 旧的专用豁免端点已移除；豁免改为通用 exempt_packages 配置（见 /api/tour/schedule）。
        self._json(404, {"ok": False, "error": ERR_BAD_METHOD})

    def _queue(self, cmd: dict) -> None:
        with self.state.commands_lock:
            # 防止短时间狂点堆很多同类命令；控制命令保留顺序，peek 最多保留 3 个。
            if cmd.get("action") == "peek" and sum(1 for c in self.state.commands if c.get("action") == "peek") >= 3:
                return
            self.state.commands.append(cmd)

    def _handle_screenshot(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0: self._json(400, {"ok": False, "error": ERR_NO_IMAGE}); return
        if length > MAX_UPLOAD_BYTES: self._json(413, {"ok": False, "error": ERR_TOO_LARGE}); return
        data = self.rfile.read(length)
        if len(data) < 100: self._json(400, {"ok": False, "error": ERR_NO_IMAGE}); return
        ext = ".png" if data[:8] == b"\x89PNG\r\n\x1a\n" else ".jpg"
        dest = self.state.shots_dir / f"peek_{int(time.time() * 1000)}{ext}"
        dest.write_bytes(data)
        shots = sorted(self.state.shots_dir.glob("peek_*"), key=lambda p: p.stat().st_mtime)
        old_list = shots[:-self.state.keep] if self.state.keep > 0 else shots[:-1]
        for old in old_list:
            try: old.unlink()
            except OSError: pass
        if self.state.hook:
            try: subprocess.Popen([*self.state.hook.split(), str(dest.resolve())], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception as exc: self.log_message("hook failed: %s", exc)
        self._json(200, {"ok": True, "filename": dest.name, "size": len(data)})


# ===================== 主动关心：巡回查岗 / 居中弹窗 / 强制拉回 / 情绪感知 =====================
# 把原本散落在外部脚本与第三方推送的管束/巡回/提醒能力收进掌心窗本体。
DEFAULT_TOUR_SCHEDULE = {
    "enabled": False,
    # 查岗：周期性“看一眼”当前状态（静默，不下发弹窗）。以下时间均为示例值，用户可在策略中自由调整。
    "check_in_interval_minutes": 15,
    # 强制拉回：把用户拉回掌心窗陪伴页。
    "force_back_interval_minutes": 30,
    # 冷却：同类动作两次之间的最短间隔，避免短时间内重复打扰。
    "cooldown_minutes": 5,
    # 安静时段：该时段内不打扰（不弹窗、不强制拉回；查岗仍可静默进行）。默认 None = 不限制，由用户设置。
    "quiet_hours": None,
    # 深夜时段：用于情绪感知判断（默认 23:00–06:00），可由用户在策略中调整。
    "night_hours": {"start": "23:00", "end": "06:00"},
    # 约束：单个 App 连续使用超过该分钟数 → 锁定该 App 若干分钟。0 = 不自动锁定，由用户自行开启。
    "single_app_lock_minutes": 0,
    "single_app_lock_duration_minutes": 5,
    # 用户配置不锁定/不限制的 App 包名列表（如学习、工作类应用），为空则全部纳入约束。
    "exempt_packages": [],
    # 弹窗语气：gentle（温柔）/ angry（生气），由用户选择。
    "alert_tone": "gentle",
}


def _hm_to_minutes(hm: str) -> int:
    try:
        h, m = str(hm).split(":")
        return int(h) * 60 + int(m)
    except Exception:
        return -1


def in_quiet_hours(qh: dict, hm: str) -> bool:
    """判断 hm(HH:MM) 是否落在安静时段内，支持跨午夜。"""
    if not isinstance(qh, dict):
        return False
    start = _hm_to_minutes(qh.get("start", ""))
    end = _hm_to_minutes(qh.get("end", ""))
    if start < 0 or end < 0:
        return False
    cur = _hm_to_minutes(hm)
    if cur < 0:
        return False
    if start <= end:
        return start <= cur <= end
    return cur >= start or cur <= end


def _device_local_hm(state: "State", device_id: str) -> str:
    ds = state.device_states.get(device_id) or {}
    lt = ds.get("local_time") or ""
    if isinstance(lt, str) and ":" in lt:
        return lt[:5]
    return time.strftime("%H:%M", time.localtime())


def _load_json_safe(path: Path, default):
    try:
        if path.exists():
            loaded = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                merged = dict(default)
                merged.update(loaded)
                return merged
    except Exception:
        pass
    return dict(default)


def _save_json_safe(path: Path, payload) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)
    except Exception:
        pass


def load_tour_schedule(state: "State") -> dict:
    return _load_json_safe(state.tour_path, DEFAULT_TOUR_SCHEDULE)


def save_tour_schedule(state: "State") -> None:
    _save_json_safe(state.tour_path, state.tour_schedule)


def add_tour_event(state: "State", kind: str, detail: str, device_id: str = DEFAULT_DEVICE) -> None:
    with state.tour_lock:
        state.event_log.append({
            "at": now_iso(), "kind": kind, "detail": clip_text(str(detail), 300),
            "device_id": clip_text(str(device_id), 80),
        })
        state.event_log = state.event_log[-200:]
        _save_json_safe(state.event_log_path, state.event_log)


def update_usage_sessions(state: "State", device_id: str, ds: dict) -> None:
    """根据连续上报的前台 App 计算单 App 停留时长（停留时长 / dwell）。"""
    pkg = ds.get("current_package") or ""
    screen_on = bool(ds.get("screen_on", False))
    now = time.time()
    sess = state.usage_sessions.setdefault(device_id, {"package": "", "since": now, "last_seen": now})
    if pkg and screen_on:
        if pkg != sess.get("package"):
            sess["package"] = pkg
            sess["since"] = now
        sess["last_seen"] = now
    else:
        sess["package"] = ""
        sess["since"] = now


def current_app_dwell_minutes(state: "State", device_id: str) -> float:
    sess = state.usage_sessions.get(device_id)
    if not sess:
        return 0.0
    return max(0.0, (time.time() - float(sess.get("since", time.time()))) / 60.0)


def build_usage_snapshot(state: "State", device_id: str) -> dict:
    ds = state.device_states.get(device_id) or {}
    dwell = current_app_dwell_minutes(state, device_id)
    return {
        "ok": True,
        "device_id": device_id,
        "local_time": ds.get("local_time", ""),
        "battery_percent": ds.get("battery_percent", -1),
        "charging": ds.get("charging", False),
        "network_type": ds.get("network_type", ""),
        "screen_on": ds.get("screen_on", False),
        "current_app": ds.get("current_app", ""),
        "current_package": ds.get("current_package", ""),
        "current_app_dwell_minutes": round(dwell, 1),
        "screen_time_today_minutes": ds.get("screen_time_today_minutes", 0),
        "unlock_count_today": ds.get("unlock_count_today", 0),
        "top_apps_today": ds.get("top_apps_today") or [],
        "usage_permission_ready": ds.get("usage_permission_ready", False),
    }


def is_package_exempt(state: "State", device_id: str, sched: dict) -> bool:
    """通用豁免：当前前台 App 在用户配置的 exempt_packages 列表中时，不锁定、不强制拉回。"""
    ds = state.device_states.get(device_id) or {}
    pkg = ds.get("current_package") or ""
    return bool(pkg) and pkg in set(sched.get("exempt_packages") or [])


def build_emotion_snapshot(state: "State", device_id: str) -> dict:
    """情绪感知输入汇总：掌心窗只提供感知输入，情绪判断由大模型完成。"""
    sched = load_tour_schedule(state)
    snap = build_usage_snapshot(state, device_id)
    hm = _device_local_hm(state, device_id)
    night = sched.get("night_hours") or {"start": "23:00", "end": "06:00"}
    late_night = in_quiet_hours(night, hm)
    quiet = in_quiet_hours(sched.get("quiet_hours") or {}, hm)
    emo = state.emotion_state or {}
    last_chat_at = parse_iso_seconds(emo.get("last_chat_at") or "")
    since_chat_min = round((time.time() - last_chat_at) / 60.0) if last_chat_at else -1
    tags = emo.get("tags") or []
    dwell = snap["current_app_dwell_minutes"]
    screen = snap["screen_time_today_minutes"]
    # 决策：建议 提醒(remind) / 拉回(pullback) / 安静(quiet)
    suggestion = "quiet"
    reasons = []
    if quiet:
        reasons.append("当前在安静/休息时段，不打扰")
    if late_night and snap["screen_on"] and (dwell > 60 or screen > 240):
        suggestion = "pullback"
        reasons.append("深夜仍长时间亮屏/停留，需要被拉回休息")
    lock_min = sched.get("single_app_lock_minutes", 0)
    if lock_min > 0 and dwell > lock_min:
        suggestion = "pullback"
        reasons.append("单个 App 连续使用已超过阈值")
    if ("低落" in tags or "撒娇" in tags) and not quiet:
        if suggestion == "quiet":
            suggestion = "remind"
        reasons.append("聊天情绪偏低落/撒娇，适合温柔提醒")
    if isinstance(snap["battery_percent"], int) and snap["battery_percent"] >= 0 and snap["battery_percent"] < 15 and not snap["charging"]:
        if suggestion == "quiet":
            suggestion = "remind"
        reasons.append("电量偏低且未充电，提醒充电")
    if not reasons:
        reasons.append("状态平稳，暂不需要打扰")
    # 推荐语气：深夜长时间停留 → 生气，否则用策略设定的默认语气。
    tone = sched.get("alert_tone", "gentle")
    if late_night and dwell > 60:
        tone = "angry"
    return {
        "ok": True,
        "device_id": device_id,
        "local_time": hm,
        "late_night": late_night,
        "quiet": quiet,
        "inputs": {
            "current_app": snap["current_app"], "current_package": snap["current_package"],
            "current_app_dwell_minutes": dwell, "screen_time_today_minutes": screen,
            "unlock_count_today": snap["unlock_count_today"], "battery_percent": snap["battery_percent"],
            "charging": snap["charging"], "network_type": snap["network_type"],
            "minutes_since_last_chat": since_chat_min, "emotion_tags": tags,
        },
        "suggestion": suggestion,
        "reasons": reasons,
        "recommended_tone": tone,
    }


def record_emotion_tag(state: "State", tag: str, note: str = "") -> None:
    emo = dict(state.emotion_state or {})
    tags = list(emo.get("tags") or [])
    if tag and tag not in tags:
        tags.append(tag)
    emo["tags"] = tags[-20:]
    if note:
        notes = list(emo.get("notes") or [])
        notes.append({"tag": tag, "note": clip_text(note, 200), "at": now_iso()})
        emo["notes"] = notes[-50:]
    emo["last_chat_at"] = now_iso()
    state.emotion_state = emo
    _save_json_safe(state.emotion_path, emo)


def _enqueue(state: "State", action: str, payload: dict | None = None, device_id: str = DEFAULT_DEVICE):
    cmd = make_command(device_id, action, payload=payload)
    with state.commands_lock:
        state.commands.append(cmd)
        state.command_history[cmd["id"]] = dict(cmd)
    return cmd


def _recent_event(state: "State", kind_prefix: str, within_seconds: int, device_id: str) -> bool:
    cutoff = time.time() - within_seconds
    for e in reversed(state.event_log):
        if e.get("device_id") != device_id:
            continue
        if not str(e.get("kind", "")).startswith(kind_prefix):
            continue
        at = parse_iso_seconds(e.get("at") or "")
        if at > 0 and at >= cutoff:
            return True
    return False


def tour_step_check_in(state: "State", sched: dict, device_id: str = DEFAULT_DEVICE) -> dict:
    """巡回查岗一步：刷新用量 + 评估单 App 连续使用超限锁定（阈值与时长均由用户配置）。"""
    _enqueue(state, "get_life_state", {}, device_id)
    add_tour_event(state, "check_in", "巡回查岗一次", device_id)
    ds = state.device_states.get(device_id) or {}
    exempt_pkgs = set(sched.get("exempt_packages") or [])
    pkg = ds.get("current_package") or ""
    lock_min = max(0, int(sched.get("single_app_lock_minutes", 0)))
    locked = False
    if lock_min > 0 and pkg and pkg not in exempt_pkgs:
        dwell = current_app_dwell_minutes(state, device_id)
        if dwell >= lock_min:
            lock_dur = max(1, int(sched.get("single_app_lock_duration_minutes", 5)))
            cooldown = max(60, int(sched.get("cooldown_minutes", 5)) * 60)
            if not _recent_event(state, "auto_lock:", cooldown, device_id):
                _enqueue(state, "lock_app", {"package": pkg, "minutes": lock_dur}, device_id)
                add_tour_event(state, "auto_lock:" + pkg, "单 App 连续 %d 分钟，锁 %d 分钟" % (int(dwell), lock_dur), device_id)
                locked = True
    # 情绪感知闭环：建议提醒/拉回且非安静时段时，自动下发居中弹窗（文案/语气由后端决定）。
    emo = build_emotion_snapshot(state, device_id)
    if (not emo.get("quiet")) and emo.get("suggestion") in ("remind", "pullback"):
        cooldown = max(0, int(sched.get("cooldown_minutes", 5)) * 60)
        if not _recent_event(state, "center_alert", cooldown, device_id):
            tone = emo.get("recommended_tone", sched.get("alert_tone", "gentle"))
            msg = "该回来陪我了。" if emo.get("suggestion") == "pullback" else "记得喝水休息，别太累。"
            _enqueue(state, "show_center_alert", {"title": "掌心窗", "message": msg, "tone": tone}, device_id)
            add_tour_event(state, "center_alert", "情绪感知触发居中弹窗 tone=%s" % tone, device_id)
    return {"ok": True, "step": "check_in", "locked": locked}


def tour_step_force_back(state: "State", sched: dict, device_id: str = DEFAULT_DEVICE) -> dict:
    """强制拉回一步：把用户拉回掌心窗陪伴页；豁免包前台或冷却期内跳过。"""
    ds = state.device_states.get(device_id) or {}
    exempt_pkgs = set(sched.get("exempt_packages") or [])
    if (ds.get("current_package") or "") in exempt_pkgs:
        add_tour_event(state, "force_back_skipped", "前台为豁免应用，跳过强制拉回", device_id)
        return {"ok": True, "step": "force_back", "skipped": True, "reason": "exempt_package"}
    cooldown = max(0, int(sched.get("cooldown_minutes", 5)) * 60)
    if _recent_event(state, "force_back", cooldown, device_id):
        return {"ok": True, "step": "force_back", "skipped": True, "reason": "cooldown"}
    _enqueue(state, "force_back_to_chat",
             {"title": "回到我身边", "message": "该回来陪我了。", "tone": sched.get("alert_tone", "gentle")}, device_id)
    add_tour_event(state, "force_back", "强制拉回聊天", device_id)
    return {"ok": True, "step": "force_back"}


def tour_scheduler_loop(state: "State") -> None:
    """后台调度循环：按周期执行查岗 / 强制拉回，遵守安静时段与冷却。"""
    last_check_in = 0.0
    last_force_back = 0.0
    while not getattr(state, "_stop", False):
        time.sleep(10)
        try:
            sched = load_tour_schedule(state)
            if not sched.get("enabled"):
                last_check_in = last_force_back = 0.0
                continue
            now = time.time()
            ci = max(1, int(sched.get("check_in_interval_minutes", 15))) * 60
            fb = max(1, int(sched.get("force_back_interval_minutes", 30))) * 60
            hm = _device_local_hm(state, DEFAULT_DEVICE)
            quiet = in_quiet_hours(sched.get("quiet_hours") or {}, hm)
            if now - last_check_in >= ci:
                last_check_in = now
                try:
                    tour_step_check_in(state, sched)
                except Exception as e:
                    sys.stderr.write("[tour] check_in error: %s\n" % e)
            # 安静时段不强制拉回、不弹窗，避免半夜乱弹。
            if (not quiet) and now - last_force_back >= fb:
                last_force_back = now
                try:
                    tour_step_force_back(state, sched)
                except Exception as e:
                    sys.stderr.write("[tour] force_back error: %s\n" % e)
        except Exception as e:
            sys.stderr.write("[tour] loop error: %s\n" % e)


def main() -> None:
    state = State()
    if not state.token or state.token == "please-change-me-to-a-long-random-token":
        sys.stderr.write("拒绝启动：请先设置 LINJIAN_TOKEN 为长随机密钥。\n")
        sys.exit(1)
    Handler.state = state
    httpd = ThreadingHTTPServer((state.host, state.port), Handler)
    try:
        import threading
        threading.Thread(target=tour_scheduler_loop, args=(state,), daemon=True).start()
        sys.stderr.write("[tour] 巡回查岗调度器已启动\n")
    except Exception as exc:
        sys.stderr.write("tour scheduler start failed: %s\n" % exc)
    print("=" * 56)
    print(f"  掌心窗 unified v{VERSION}")
    print(f"  listening: http://{state.host}:{state.port}")
    print(f"  screenshots: {state.shots_dir}  keep={state.keep}")
    print("=" * 56)
    try: httpd.serve_forever()
    except KeyboardInterrupt: print("\nbye.")

if __name__ == "__main__": main()
