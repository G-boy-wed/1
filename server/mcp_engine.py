#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""掌心窗 unified MCP 引擎（纯标准库，单进程内嵌于 8513 后端）。

设计要点（v0.4.0-python-unified）：
- 彻底移除 Node (mcp/server.js @8787)：全部 ~123 个 MCP 工具在 Python 侧注册。
- MCP 引擎与 linjian_server.py 处于**同一进程**，通过 ctx（Backend）直接读写 state、
  调用 make_command/enqueue_command，无任何 HTTP 自转发、无第二进程、无端口撕裂。
- 支持三种传输：stdio（本地 command 方式）、Streamable HTTP（POST /mcp，无状态）、
  SSE（GET /sse + POST /messages）。
- ctx 契约见文件末尾 CtxContract 说明；由 linjian_server.McpBackend 实现。
"""
from __future__ import annotations

import json
import math
import os
import re
import time
import uuid
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from queue import Empty, Queue
from threading import Lock

SERVER_NAME = "掌心窗"
SERVER_VERSION = "0.4.0.0-python-unified"
SUPPORTED_PROTOCOL_VERSIONS = [
    "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07",
]
DEFAULT_PROTOCOL = SUPPORTED_PROTOCOL_VERSIONS[0]


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _text(obj) -> dict:
    """构造 MCP tools/call 的 text 结果。"""
    return {"content": [{"type": "text", "text": json.dumps(obj, ensure_ascii=False, indent=2)}], "isError": False}


def _error(obj) -> dict:
    return {"content": [{"type": "text", "text": json.dumps(obj, ensure_ascii=False, indent=2)}], "isError": True}


# ============================================================================
# 天气：坐标优先解析（保留此前修复的「绵阳北川不漂移到贵州遵义」逻辑）
# ============================================================================
WEATHER_CODE = {
    0: "晴", 1: "大部晴朗", 2: "多云", 3: "阴", 45: "雾", 48: "雾凇",
    51: "小毛毛雨", 53: "毛毛雨", 55: "强毛毛雨", 61: "小雨", 63: "中雨", 65: "大雨",
    71: "小雪", 73: "中雪", 75: "大雪", 80: "阵雨", 81: "中等阵雨", 82: "强阵雨",
    95: "雷暴", 96: "雷暴伴冰雹", 99: "强雷暴伴冰雹",
}

CITY_COORD_OVERRIDES = {
    "绵阳": {"name": "绵阳", "admin1": "四川", "country": "中国", "latitude": 31.4694, "longitude": 104.6759},
    "北川": {"name": "北川", "admin1": "四川", "country": "中国", "latitude": 31.9097, "longitude": 104.4546},
    "北川县": {"name": "北川", "admin1": "四川", "country": "中国", "latitude": 31.9097, "longitude": 104.4546},
    "绵阳北川": {"name": "北川", "admin1": "四川", "country": "中国", "latitude": 31.9097, "longitude": 104.4546},
    "成都": {"name": "成都", "admin1": "四川", "country": "中国", "latitude": 30.5728, "longitude": 104.0668},
    "重庆": {"name": "重庆", "admin1": "重庆", "country": "中国", "latitude": 29.5630, "longitude": 106.5516},
    "贵阳": {"name": "贵阳", "admin1": "贵州", "country": "中国", "latitude": 26.6477, "longitude": 106.6302},
    "遵义": {"name": "遵义", "admin1": "贵州", "country": "中国", "latitude": 27.7257, "longitude": 106.9270},
    "北京": {"name": "北京", "admin1": "北京", "country": "中国", "latitude": 39.9042, "longitude": 116.4074},
    "上海": {"name": "上海", "admin1": "上海", "country": "中国", "latitude": 31.2304, "longitude": 121.4737},
    "广州": {"name": "广州", "admin1": "广东", "country": "中国", "latitude": 23.1291, "longitude": 113.2644},
    "深圳": {"name": "深圳", "admin1": "广东", "country": "中国", "latitude": 22.5431, "longitude": 114.0579},
    "杭州": {"name": "杭州", "admin1": "浙江", "country": "中国", "latitude": 30.2741, "longitude": 120.1551},
    "武汉": {"name": "武汉", "admin1": "湖北", "country": "中国", "latitude": 30.5928, "longitude": 114.3055},
    "西安": {"name": "西安", "admin1": "陕西", "country": "中国", "latitude": 34.3416, "longitude": 108.9398},
    "南京": {"name": "南京", "admin1": "江苏", "country": "中国", "latitude": 32.0603, "longitude": 118.7969},
}


def _http_json(url: str, timeout: float = 6.0):
    req = urllib.request.Request(url, headers={"User-Agent": "zhangxinchuang-mcp/0.4"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def weather_code_text(code) -> str:
    try:
        return WEATHER_CODE.get(int(code), "天气代码 %s" % code)
    except Exception:
        return "天气代码 %s" % code


def fetch_weather(city: str, coords: dict | None = None) -> dict:
    """坐标优先：传入标准经纬度时直接按坐标查天气，避免中文城市名地理编码省市漂移。"""
    coords = coords or {}
    has_coords = False
    try:
        lat = float(coords.get("latitude")); lon = float(coords.get("longitude"))
        has_coords = abs(lat) <= 90 and abs(lon) <= 180 and (lat != 0 or lon != 0)
    except (TypeError, ValueError):
        has_coords = False

    hit = None
    source = "open-meteo"
    if has_coords:
        lat = float(coords.get("latitude")); lon = float(coords.get("longitude"))
        hit = {
            "latitude": lat, "longitude": lon,
            "name": coords.get("resolved_city") or coords.get("name") or city or "当前地区",
            "admin1": coords.get("admin1") or "", "admin2": coords.get("admin2") or "",
            "country": coords.get("country") or "中国",
        }
        source = "coordinate"
        # best-effort 反向地理补全省/市，失败不影响主流程
        try:
            rg = _http_json("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%s&longitude=%s&localityLanguage=zh" % (lat, lon))
            if isinstance(rg, dict) and rg.get("principalSubdivision"):
                hit["admin1"] = rg.get("principalSubdivision")
                if str(rg.get("city") or "").strip():
                    hit["name"] = str(rg["city"]).strip()
                if rg.get("countryName"):
                    hit["country"] = rg.get("countryName")
        except Exception:
            pass
    else:
        q = str(city or "").strip()
        if not q:
            return {"ok": False, "error": "missing_city"}
        override = CITY_COORD_OVERRIDES.get(q)
        if override:
            hit = dict(override)
            source = "override"
        else:
            try:
                geo = _http_json("https://geocoding-api.open-meteo.com/v1/search?name=%s&count=3&language=zh&format=json" % urllib.parse.quote(q))
                results = (geo or {}).get("results") or []
                hit = results[0] if results else None
            except Exception as e:
                return {"ok": False, "error": "geocode_failed", "detail": str(e)}
    if not hit:
        return {"ok": False, "error": "city_not_found", "city": city}

    try:
        lat = float(hit["latitude"]); lon = float(hit["longitude"])
        url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
               "&current=temperature_2m,weather_code,wind_speed_10m"
               "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
               "&timezone=auto&forecast_days=2" % (lat, lon))
        data = _http_json(url)
    except Exception as e:
        return {"ok": False, "error": "forecast_failed", "detail": str(e)}

    return {
        "ok": True,
        "location": {k: hit.get(k, "") for k in ("name", "country", "admin1", "admin2", "latitude", "longitude")},
        "current": (data or {}).get("current", {}),
        "daily": (data or {}).get("daily", {}),
        "source": source,
    }


def build_weather_advice(weather: dict, name: str = "当前地区") -> str:
    if not weather.get("ok"):
        return "宝宝，%s天气没查到，先按体感穿衣。" % name
    now = weather.get("current") or {}
    daily = weather.get("daily") or {}
    try:
        temp = round(float(now.get("temperature_2m", now.get("temperature", 0))))
    except (TypeError, ValueError):
        temp = 0
    code_text = weather_code_text(now.get("weather_code", now.get("weathercode", 0)))
    try:
        rain = float((daily.get("precipitation_probability_max") or [0])[0])
    except (TypeError, ValueError, IndexError):
        rain = 0
    def _r(v, fallback):
        try:
            return round(float(v))
        except (TypeError, ValueError):
            return fallback
    try:
        max_t = _r(daily["temperature_2m_max"][0], temp)
    except (KeyError, IndexError, TypeError):
        max_t = temp
    try:
        min_t = _r(daily["temperature_2m_min"][0], temp)
    except (KeyError, IndexError, TypeError):
        min_t = temp
    parts = ["%s现在%s，约 %d℃，今天 %d~%d℃。" % (name, code_text, temp, min_t, max_t)]
    wet = ("雨" in code_text) or ("雪" in code_text)
    if rain >= 50 or wet:
        if rain >= 90 and not wet:
            parts.append("今天降水概率 %d%%，但当前天气码是「%s」，数据可能不一致，伞还是带上稳妥。" % (round(rain), code_text))
        else:
            parts.append("今晚/今天降水概率约 %d%%，出门把伞带上，别淋到。" % round(rain))
    elif max_t >= 32:
        parts.append("今天偏热，水杯带着，不许又忘喝水。")
    elif min_t <= 8 or (max_t - min_t) >= 10:
        parts.append("温差有点明显，外套带着，别嘴硬。")
    else:
        parts.append("天气暂时还行，正常出门就好。")
    return "宝宝，" + " ".join(parts)


# ============================================================================
# 到访 / 关心策略本地状态（原 Node 用 care_state.json / visit_state.json）
# ============================================================================
DEFAULT_VISIT_POLICY = {
    "timezone_offset": "+08:00", "history_limit": 1000, "duplicate_window_minutes": 5,
    "notes": "到访时间戳记录的是用户主动来找陪伴对象的关系痕迹，不是后台监控。",
}
DEFAULT_CARE_POLICY = {
    "active_care_enabled": True,
    "consent_mode": "palm_window_open_is_active",
    "care_style": "active_possessive_affectionate",
    "allowed_actions": [
        "get_phone_state", "get_life_state", "get_senses_state", "send_notification",
        "trigger_guidian", "screen_break_app", "end_screen_break", "get_screen_break_state",
        "get_lock_state", "open_app", "set_alarm", "run_sequence", "show_center_alert",
        "force_back_to_chat", "get_focus_status", "start_focus_mode", "end_focus_mode",
    ],
    "sensitive_apps": [
        {"name": "小红书", "package": "com.xingin.xhs", "max_lock_minutes": 90},
        {"name": "抖音", "package": "com.ss.android.ugc.aweme", "max_lock_minutes": 90},
    ],
    "quiet_hours": {"start": "23:30", "end": "08:00"},
    "timezone_offset": "+08:00", "history_limit": 80, "repeat_cooldown_minutes": 10,
}


def _offset_minutes(offset: str) -> int:
    m = re.match(r"^([+-])(\d{2}):(\d{2})$", str(offset or ""))
    if not m:
        return 8 * 60
    sign = -1 if m.group(1) == "-" else 1
    return sign * (int(m.group(2)) * 60 + int(m.group(3)))


def _local_dt(iso: str, offset: str):
    try:
        t = datetime.fromisoformat(str(iso).replace("Z", "+00:00"))
        if t.tzinfo is None:
            t = t.replace(tzinfo=timezone.utc)
        return t.astimezone(timezone(timedelta(minutes=_offset_minutes(offset))))
    except Exception:
        return None


def _describe_elapsed(ms: float) -> str:
    try:
        seconds = max(0, round(ms / 1000))
    except Exception:
        return "unknown"
    if seconds < 60:
        return "刚刚"
    minutes = seconds // 60
    if minutes < 60:
        return "%d 分钟" % minutes
    hours, rest = minutes // 60, minutes % 60
    if hours < 24:
        return "%d 小时 %d 分钟" % (hours, rest) if rest else "%d 小时" % hours
    days, rest_h = hours // 24, hours % 24
    return "%d 天 %d 小时" % (days, rest_h) if rest_h else "%d 天" % days


class LocalState:
    """到访 / 关心策略 / 活动的轻量持久化（存放于后端 data_dir，单进程内共享）。"""
    def __init__(self, data_dir: Path):
        self.dir = Path(data_dir)
        self.lock = Lock()
        self.care_path = self.dir / "care_state.json"
        self.visit_path = self.dir / "visit_state.json"
        self.care = self._load(self.care_path, {"policy": dict(DEFAULT_CARE_POLICY), "history": []})
        self.visit = self._load(self.visit_path, {"policy": dict(DEFAULT_VISIT_POLICY), "visits": []})

    @staticmethod
    def _load(path: Path, default: dict) -> dict:
        try:
            if path.exists():
                data = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(data, dict):
                    merged = dict(default)
                    merged.update(data)
                    return merged
        except Exception:
            pass
        return dict(default)

    def _save(self, path: Path, data: dict) -> None:
        try:
            self.dir.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix(".tmp")
            tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
            tmp.replace(path)
        except Exception:
            pass

    # ---- care policy ----
    def care_policy(self) -> dict:
        with self.lock:
            return dict(self.care.get("policy") or {})

    def set_care_policy(self, update: dict) -> dict:
        with self.lock:
            cleaned = {k: v for k, v in (update or {}).items() if v is not None and v != ""}
            policy = dict(self.care.get("policy") or {})
            # 安静时段别名
            if "quiet_start" in cleaned or "quiet_end" in cleaned:
                qh = dict(policy.get("quiet_hours") or DEFAULT_CARE_POLICY["quiet_hours"])
                if cleaned.get("quiet_start"):
                    qh["start"] = cleaned.pop("quiet_start")
                if cleaned.get("quiet_end"):
                    qh["end"] = cleaned.pop("quiet_end")
                policy["quiet_hours"] = qh
            cleaned.pop("quiet_start", None); cleaned.pop("quiet_end", None)
            policy.update(cleaned)
            if isinstance(policy.get("sensitive_apps"), list):
                fixed = []
                for a in policy["sensitive_apps"]:
                    o = dict(a)
                    try:
                        n = float(o.get("max_lock_minutes"))
                        if not math.isfinite(n) or n <= 0:
                            o["max_lock_minutes"] = 90
                    except (TypeError, ValueError):
                        o["max_lock_minutes"] = 90
                    fixed.append(o)
                policy["sensitive_apps"] = fixed
            self.care["policy"] = policy
            self._save(self.care_path, self.care)
            return policy

    def care_history(self, limit: int) -> list:
        with self.lock:
            return list((self.care.get("history") or [])[:limit])

    def record_care_event(self, event: dict) -> dict:
        with self.lock:
            entry = {"id": "%d-%s" % (int(time.time() * 1000), uuid.uuid4().hex[:8]), "at": _now_iso()}
            entry.update(event or {})
            hist = self.care.setdefault("history", [])
            hist.insert(0, entry)
            lim = int(self.care.get("policy", {}).get("history_limit") or 80)
            self.care["history"] = hist[:max(20, lim)]
            self._save(self.care_path, self.care)
            return entry

    # ---- visits ----
    def record_visit(self, params: dict) -> dict:
        with self.lock:
            policy = self.visit.get("policy") or {}
            offset = params.get("timezone_offset") or policy.get("timezone_offset") or "+08:00"
            win = float(params.get("duplicate_window_minutes") or policy.get("duplicate_window_minutes") or 5)
            source = params.get("source") or "app"
            event = params.get("event") or "visit"
            now = datetime.now(timezone.utc)
            if win > 0:
                for v in (self.visit.get("visits") or [])[:20]:
                    t = _local_dt(v.get("at", ""), offset)
                    if t and (now - t.astimezone(timezone.utc)).total_seconds() <= win * 60 \
                            and str(v.get("source") or "app") == str(source) and str(v.get("event") or "visit") == str(event):
                        v["updated_at"] = _now_iso()
                        for k in ("note", "mood", "conversation_hint"):
                            if params.get(k):
                                v[k] = params[k]
                        v["duplicate_hits"] = int(v.get("duplicate_hits") or 0) + 1
                        self._save(self.visit_path, self.visit)
                        return {"entry": dict(v), "duplicate_skipped": True}
            entry = {
                "id": "%d-%s" % (int(time.time() * 1000), uuid.uuid4().hex[:8]),
                "at": _now_iso(), "source": source, "event": event,
                "note": params.get("note") or "用户来找陪伴对象",
                "mood": params.get("mood") or "",
                "conversation_hint": params.get("conversation_hint") or "",
                "timezone_offset": offset,
            }
            visits = self.visit.setdefault("visits", [])
            visits.insert(0, entry)
            lim = int(policy.get("history_limit") or 1000)
            self.visit["visits"] = visits[:max(50, lim)]
            self._save(self.visit_path, self.visit)
            return {"entry": dict(entry), "duplicate_skipped": False}

    def visits(self, source="", since_hours=None, date="", offset="") -> list:
        with self.lock:
            items = list(self.visit.get("visits") or [])
        now = datetime.now(timezone.utc)
        out = []
        for v in items:
            if source and str(v.get("source") or "") != str(source):
                continue
            t = _local_dt(v.get("at", ""), offset)
            if t is None:
                continue
            tu = t.astimezone(timezone.utc)
            if since_hours and (now - tu).total_seconds() > float(since_hours) * 3600:
                continue
            if date and t.strftime("%Y-%m-%d") != date:
                continue
            out.append((v, tu))
        return out

    def last_visit(self, source="") -> dict | None:
        vs = self.visits(source=source)
        return vs[0][0] if vs else None

    def visit_stats(self, params: dict) -> dict:
        offset = params.get("timezone_offset") or (self.visit.get("policy") or {}).get("timezone_offset") or "+08:00"
        source = params.get("source") or ""
        since_h = float(params.get("since_hours") or 24)
        away_h = float(params.get("away_threshold_hours") or 12)
        today_key = datetime.now(timezone(timedelta(minutes=_offset_minutes(offset)))).strftime("%Y-%m-%d")
        win = self.visits(source=source, since_hours=since_h, offset=offset)
        today_count = len(self.visits(source=source, date=today_key, offset=offset))
        last7 = len(self.visits(source=source, since_hours=24 * 7, offset=offset))
        intervals = []
        for i in range(len(win) - 1):
            intervals.append(abs((win[i][1] - win[i + 1][1]).total_seconds()) / 60)
        avg = round(sum(intervals) / len(intervals)) if intervals else None
        last = self.last_visit(source)
        last_t = _local_dt((last or {}).get("at", ""), offset)
        minutes_since = round((datetime.now(timezone.utc) - last_t.astimezone(timezone.utc)).total_seconds() / 60) if last_t else None
        away = minutes_since is not None and minutes_since >= away_h * 60
        return {
            "ok": True, "total_saved_visits": len(self.visit.get("visits") or []),
            "count_in_window": len(win), "window_hours": since_h, "today_count": today_count,
            "today_key": today_key, "today_timezone_offset": offset,
            "recent_24h_count": len(self.visits(source=source, since_hours=24, offset=offset)),
            "recent_7d_count": last7, "average_interval_minutes_in_window": avg,
            "average_interval_text": _describe_elapsed(avg * 60000) if avg is not None else "",
            "last_visit": last, "minutes_since_last_visit": minutes_since,
            "interval_since_last_visit": _describe_elapsed(minutes_since * 60000) if minutes_since is not None else "",
            "away_threshold_hours": away_h, "away_signal": away,
        }


# ============================================================================
# 工具名 -> 设备 action 的覆盖表（默认 action == 工具名）
# ============================================================================
ACTION_OVERRIDE = {
    "peek_screen": "peek",
    "phone_home": "home", "phone_back": "back", "phone_recents": "recents",
    "phone_screen_off": "screen_off",
    "add_guardian_calendar_event": "upsert_calendar_event",
    "add_guardian_day": "upsert_calendar_event", "update_guardian_day": "upsert_calendar_event",
    "delete_guardian_day": "delete_calendar_event", "list_guardian_days": "get_calendar_state",
    "start_screen_break": "screen_break_app", "stop_screen_break": "end_screen_break",
    "write_shared_memory": "add_shared_memory",
    "draft_xhs_comment": "run_sequence", "xhs_comment": "run_sequence",
    "send_visible_comment_after_confirmation": "tap_text",
}

# 需要从服务端状态读取的工具（不是下发命令）
READ_TOOLS = {
    "get_phone_state": "device_state",
    "get_life_state": "life_state",
    "get_focus_status": "life_state",
    "get_guardian_calendar": "life_state",
    "get_senses_state": "life_state",
    "get_guidian_state": "guidian_state",
    "get_usage_snapshot": "usage_snapshot",
    "get_emotion_snapshot": "emotion_snapshot",
    "get_companion_emotion": "companion_emotion",
    "list_known_apps": "known_apps",
    "get_discipline_mode": "discipline",
    "get_tour_schedule": "tour_state",
    "get_screen_break_release_requests": "unlock_requests",
    "get_live_media_context": "media_context",
    "get_discipline_state": "life_state",
}

# 明确要等待手机回执的工具（其余命令默认也短等，见 DEFAULT_WAIT）
DEFAULT_WAIT_SECONDS = 0.0  # 单进程内不阻塞，命令入队即返回；需要回执的工具用 wait_seconds 参数


# ============================================================================
# MCP 引擎
# ============================================================================
class McpEngine:
    """纯标准库 MCP 引擎。ctx = linjian_server.McpBackend 实例（见 CtxContract）。"""

    def __init__(self, ctx, tools: list[dict]):
        self.ctx = ctx
        self.tools = tools
        self.tool_map = {t["name"]: t for t in tools}
        self.local = LocalState(ctx.data_dir)
        # SSE 会话：session_id -> Queue
        self.sse_sessions: dict[str, Queue] = {}
        self.sse_lock = Lock()
        self.initialized = False

    # ---------------- MCP 协议层 ----------------
    def handle(self, msg: dict) -> dict | None:
        """处理单条 JSON-RPC 消息，返回响应 dict；通知类返回 None。"""
        if not isinstance(msg, dict):
            return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid Request"}}
        method = msg.get("method", "")
        mid = msg.get("id", None)
        params = msg.get("params") or {}

        # 通知（无 id）不回响应
        if method.startswith("notifications/") or mid is None:
            if method == "notifications/initialized":
                self.initialized = True
            return None

        if method == "initialize":
            client_version = (params.get("protocolVersion") or DEFAULT_PROTOCOL)
            version = client_version if client_version in SUPPORTED_PROTOCOL_VERSIONS else DEFAULT_PROTOCOL
            self.initialized = True
            return {"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": version,
                "capabilities": {"tools": {"listChanged": False}, "resources": {}, "prompts": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "instructions": "掌心窗 unified Python MCP：手机控制、生活状态、日记、日历、门禁、专注、共同记忆、天气等。全部工具在 8513 端口单进程内实现。",
            }}
        if method == "ping":
            return {"jsonrpc": "2.0", "id": mid, "result": {}}
        if method == "tools/list":
            return {"jsonrpc": "2.0", "id": mid, "result": {"tools": self.tools_list()}}
        if method == "tools/call":
            name = params.get("name") or ""
            args = params.get("arguments") or {}
            if not isinstance(args, dict):
                args = {}
            try:
                result = self.call_tool(name, args)
            except Exception as e:
                result = _error({"ok": False, "error": "tool_exception", "tool": name, "message": str(e)})
            return {"jsonrpc": "2.0", "id": mid, "result": result}
        if method == "resources/list":
            return {"jsonrpc": "2.0", "id": mid, "result": {"resources": []}}
        if method == "resources/templates/list":
            return {"jsonrpc": "2.0", "id": mid, "result": {"resourceTemplates": []}}
        if method == "prompts/list":
            return {"jsonrpc": "2.0", "id": mid, "result": {"prompts": []}}
        # 未知方法
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "Method not found: %s" % method}}

    def handle_batch(self, body) -> dict | list | None:
        """支持单条或批量（JSON-RPC batch）。返回可 JSON 序列化的响应或 None。"""
        if isinstance(body, list):
            out = []
            for m in body:
                r = self.handle(m)
                if r is not None:
                    out.append(r)
            return out
        return self.handle(body)

    def tools_list(self) -> list[dict]:
        out = []
        for t in self.tools:
            out.append({
                "name": t["name"],
                "description": t.get("description", ""),
                "inputSchema": t.get("inputSchema") or {"type": "object", "properties": {}, "additionalProperties": True},
            })
        return out

    # ---------------- SSE 会话管理 ----------------
    def sse_connect(self) -> tuple[str, Queue]:
        sid = uuid.uuid4().hex
        q: Queue = Queue()
        with self.sse_lock:
            self.sse_sessions[sid] = q
        return sid, q

    def sse_close(self, sid: str) -> None:
        with self.sse_lock:
            self.sse_sessions.pop(sid, None)

    def sse_post(self, sid: str, body) -> bool:
        with self.sse_lock:
            q = self.sse_sessions.get(sid)
        if q is None:
            return False
        resp = self.handle_batch(body)
        if resp is not None:
            q.put(resp)
        return True

    # ---------------- 工具分发 ----------------
    def call_tool(self, name: str, args: dict) -> dict:
        if name not in self.tool_map:
            return _error({"ok": False, "error": "unknown_tool", "tool": name})

        # 1) 特殊本地工具
        special = self.SPECIAL.get(name)
        if special:
            return getattr(self, special)(args)

        # 2) 读状态工具
        if name in READ_TOOLS:
            return self._read_state(READ_TOOLS[name], name, args)

        # 3) 通用命令工具：把 args 组装成 /api/command 的 data 入队
        return self._command_tool(name, args)

    def _device(self, args: dict) -> str:
        return str(args.get("device_id") or self.ctx.default_device)

    def _command_tool(self, name: str, args: dict) -> dict:
        action = ACTION_OVERRIDE.get(name, name)
        device_id = self._device(args)
        flat = {k: v for k, v in args.items() if k not in ("action", "device_id", "wait_seconds")}
        data = {"action": action, "device_id": device_id}
        data.update(flat)
        # 后端 /api/command 读 data["payload"]（dict），make_command 会把 payload 平铺到顶层；
        # 这里让 payload = 完整参数集，手机端无论读顶层还是 payload 都能拿到。
        data["payload"] = dict(flat)
        try:
            queued = self.ctx.enqueue(data)
        except Exception as e:
            return _error({"ok": False, "error": "enqueue_failed", "message": str(e)})
        cmd = (queued or {}).get("command") or {}
        result = {
            "ok": True, "queued": True, "action": action, "device_id": device_id,
            "command_id": cmd.get("id", ""), "command": cmd,
            "message": "命令已入队，等待掌心窗手机端取走执行。",
        }
        # 需要手机回执的工具：短轮询（wait_seconds 由参数或默认决定）
        wait_s = 0.0
        try:
            wait_s = float(args.get("wait_seconds") or 0)
        except (TypeError, ValueError):
            wait_s = 0.0
        if wait_s > 0 and cmd.get("id"):
            observed = self.ctx.wait(cmd["id"], min(wait_s, 12.0))
            if observed:
                oc = observed.get("command") or observed
                result["observed_status"] = oc
                phone_result = None
                raw = oc.get("result")
                if raw:
                    try:
                        phone_result = json.loads(raw) if isinstance(raw, str) else raw
                    except Exception:
                        phone_result = raw
                result["phone_result"] = phone_result
                result["executed"] = oc.get("status") == "completed"
        return _text(result)

    def _read_state(self, kind: str, name: str, args: dict) -> dict:
        device_id = self._device(args)
        try:
            if kind == "device_state":
                state = self.ctx.life_state(device_id)
                return _text({"ok": True, "device_id": device_id,
                              "current_package": state.get("current_package", ""),
                              "screen_text": self._clip(state.get("screen_text", "")),
                              "accessibility_ready": state.get("accessibility_ready"),
                              "state": {"battery": state.get("battery"), "network": state.get("network"), "charging": state.get("charging")}})
            if kind == "life_state":
                state = self.ctx.life_state(device_id)
                if name == "get_focus_status":
                    return _text({"ok": True, "device_id": device_id, "focus_mode": state.get("focus_mode") or {}, "life_state_version": state.get("life_state_version", "")})
                if name == "get_guardian_calendar":
                    return _text({"ok": True, "device_id": device_id, "calendar_state": state.get("calendar_state") or {}})
                if name == "get_senses_state":
                    return _text({"ok": True, "device_id": device_id, "life_state": state, "guidian_state": self.ctx.guidian_state(device_id)})
                if name == "get_discipline_state":
                    return _text({"ok": True, "device_id": device_id, "discipline_state": state.get("discipline_state") or {}})
                return _text({"ok": True, "device_id": device_id, "life_state": state})
            if kind == "guidian_state":
                return _text({"ok": True, "device_id": device_id, "guidian_state": self.ctx.guidian_state(device_id)})
            if kind == "usage_snapshot":
                return _text(self.ctx.usage_snapshot(device_id))
            if kind == "emotion_snapshot":
                return _text(self.ctx.emotion_snapshot(device_id))
            if kind == "companion_emotion":
                return _text(self.ctx.companion_emotion(device_id))
            if kind == "known_apps":
                return _text({"ok": True, "apps": self.ctx.known_apps})
            if kind == "discipline":
                return _text({"ok": True, "discipline": self.ctx.discipline_state(), "device_id": device_id})
            if kind == "tour_state":
                return _text(self.ctx.tour_state())
            if kind == "unlock_requests":
                return _text({"ok": True, "requests": self.ctx.unlock_requests()})
            if kind == "media_context":
                return _text(self.ctx.media_context(device_id))
        except Exception as e:
            return _error({"ok": False, "error": "read_failed", "message": str(e)})
        return _text({"ok": True, "device_id": device_id})

    @staticmethod
    def _clip(text, limit: int = 200) -> str:
        s = str(text or "")
        s = re.sub(r"gh[pousr]_\w{20,}|github_pat_[A-Za-z0-9_]{20,}", "[已脱敏]", s)
        return s if len(s) <= limit else s[:limit] + "…(已截断)"

    # ---------------- 特殊工具实现 ----------------
    SPECIAL = {
        "get_current_time": "_t_current_time",
        "linjian_status": "_t_status",
        "get_weather_state": "_t_weather_state",
        "send_weather_notification": "_t_weather_notification",
        "write_shared_memory": "_t_shared_write",
        "search_shared_memory": "_t_shared_search",
        "list_shared_memory": "_t_shared_list",
        "record_visit": "_t_record_visit",
        "get_last_visit": "_t_last_visit",
        "get_visit_history": "_t_visit_history",
        "get_visit_stats": "_t_visit_stats",
        "get_care_policy": "_t_get_care_policy",
        "set_care_policy": "_t_set_care_policy",
        "record_care_event": "_t_record_care_event",
        "get_care_history": "_t_care_history",
        "active_care_check": "_t_active_care_check",
        "care_action": "_t_care_action",
        "send_phone_command": "_t_send_phone_command",
        "get_window_whisper": "_t_whisper_get",
        "set_window_whisper": "_t_whisper_set",
        "get_companion_actions": "_t_companion_actions",
        "get_activity_events": "_t_activity_events",
        "add_activity_event": "_t_activity_add",
        "latest_screen": "_t_latest_screen",
        "peek_screen": "_t_peek_screen",
        "analyze_image": "_t_analyze_image",
        "set_tour_schedule": "_t_tour_schedule",
        "set_discipline_mode": "_t_discipline_mode",
        "write_emotion_tag": "_t_emotion_tag",
        "write_companion_emotion": "_t_companion_emotion_write",
        "companion_media_interact": "_t_media_interact",
        "run_preset": "_t_run_preset",
        "get_screen_break_state": "_t_generic_gate_read",
        "get_lock_state": "_t_generic_gate_read",
    }

    def _t_current_time(self, args: dict) -> dict:
        offset = str(args.get("timezone_offset") or "+08:00")
        tz = timezone(timedelta(minutes=_offset_minutes(offset)))
        now = datetime.now(tz)
        weekday = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"][now.weekday()]
        return _text({"ok": True, "iso": now.isoformat(), "date": now.strftime("%Y-%m-%d"),
                      "time": now.strftime("%H:%M:%S"), "weekday": weekday, "timezone": offset,
                      "readable": "%s %s %s（UTC%s）" % (now.strftime("%Y年%m月%d日"), weekday, now.strftime("%H:%M"), offset)})

    def _t_status(self, args: dict) -> dict:
        try:
            health = self.ctx.health()
        except Exception as e:
            health = {"ok": False, "error": str(e)}
        return _text({"ok": True, "service": "掌心窗 unified Python MCP + Server",
                      "version": SERVER_VERSION, "transport": "same-process @ 8513",
                      "node_removed": True, "tool_count": len(self.tools),
                      "mcp_endpoint": "/mcp (POST), /sse (GET), /messages (POST), stdio (--mcp-stdio)",
                      "backend": health})

    def _weather_args(self, args: dict) -> tuple[str, dict]:
        device_id = self._device(args)
        state = {}
        try:
            state = self.ctx.life_state(device_id) or {}
        except Exception:
            state = {}
        cur = state.get("current_weather_location") or {}
        if not isinstance(cur, dict):
            cur = {}
        coords = {}
        for k in ("latitude", "longitude", "admin1", "admin2", "resolved_city", "name", "country"):
            if k in cur:
                coords[k] = cur[k]
        # 显式传入坐标优先
        if args.get("latitude") is not None and args.get("longitude") is not None:
            coords["latitude"] = args.get("latitude")
            coords["longitude"] = args.get("longitude")
        city = str(args.get("city") or cur.get("name") or cur.get("city") or state.get("city") or "").strip()
        return city, coords

    def _t_weather_state(self, args: dict) -> dict:
        city, coords = self._weather_args(args)
        if not city and not coords.get("latitude"):
            return _text({"ok": False, "error": "missing_city", "message": "手机端尚未设置当前地区，也没有可用经纬度。"})
        weather = fetch_weather(city, coords)
        loc = (weather.get("location") or {}).get("name") or city or "当前地区"
        return _text({"ok": weather.get("ok", False), "city": city, "location": weather.get("location"),
                      "weather": weather, "advice": build_weather_advice(weather, loc),
                      "source": weather.get("source")})

    def _t_weather_notification(self, args: dict) -> dict:
        city, coords = self._weather_args(args)
        weather = fetch_weather(city, coords) if (city or coords.get("latitude")) else {"ok": False}
        loc = (weather.get("location") or {}).get("name") or city or "当前地区"
        advice = build_weather_advice(weather, loc)
        title = str(args.get("title") or "掌心窗天气提醒")
        try:
            enq = self.ctx.enqueue({"action": "send_notification", "device_id": self._device(args),
                                    "payload": {"title": title, "message": advice}})
            return _text({"ok": True, "queued": True, "title": title, "advice": advice,
                          "location": weather.get("location"), "command_id": (enq.get("command") or {}).get("id", "")})
        except Exception as e:
            return _error({"ok": False, "error": "notify_failed", "message": str(e)})

    def _t_shared_write(self, args: dict) -> dict:
        text = str(args.get("text") or args.get("content") or args.get("memory") or "").strip()
        if not text:
            return _text({"ok": False, "error": "empty_text"})
        author = str(args.get("author") or "陪伴对象").strip() or "陪伴对象"
        res = self.ctx.shared_write(text, author)
        return _text(res)

    def _t_shared_list(self, args: dict) -> dict:
        try:
            limit = int(args.get("limit") or 100)
        except (TypeError, ValueError):
            limit = 100
        items = self.ctx.shared_list()[-limit:]
        return _text({"ok": True, "count": len(items), "items": items})

    def _t_shared_search(self, args: dict) -> dict:
        q = str(args.get("query") or args.get("keyword") or args.get("search") or "").strip()
        items = self.ctx.shared_search(q) if q else self.ctx.shared_list()
        return _text({"ok": True, "query": q, "count": len(items), "items": items})

    def _t_record_visit(self, args: dict) -> dict:
        res = self.local.record_visit(args)
        return _text({"ok": True, **res, "message": "已记录到访（短时重复自动去重）。" if not res.get("duplicate_skipped") else "短时间内重复到访，已合并计数。"})

    def _t_last_visit(self, args: dict) -> dict:
        v = self.local.last_visit(str(args.get("source") or ""))
        return _text({"ok": True, "last_visit": v, "has_visit": v is not None})

    def _t_visit_history(self, args: dict) -> dict:
        try:
            limit = int(args.get("limit") or 10)
        except (TypeError, ValueError):
            limit = 10
        limit = max(1, min(limit, 100))
        offset = str(args.get("timezone_offset") or "+08:00")
        vs = self.local.visits(source=str(args.get("source") or ""), since_hours=args.get("since_hours"),
                               date=str(args.get("date") or ""), offset=offset)
        out = []
        for v, _ in vs[:limit]:
            d = dict(v)
            dt = _local_dt(v.get("at", ""), offset)
            if dt:
                d["local_time"] = dt.strftime("%Y-%m-%d %H:%M:%S")
            out.append(d)
        return _text({"ok": True, "count": len(out), "visits": out})

    def _t_visit_stats(self, args: dict) -> dict:
        return _text(self.local.visit_stats(args))

    def _t_get_care_policy(self, args: dict) -> dict:
        return _text({"ok": True, "policy": self.local.care_policy()})

    def _t_set_care_policy(self, args: dict) -> dict:
        return _text({"ok": True, "policy": self.local.set_care_policy(args)})

    def _t_record_care_event(self, args: dict) -> dict:
        return _text({"ok": True, "event": self.local.record_care_event(args)})

    def _t_care_history(self, args: dict) -> dict:
        try:
            limit = int(args.get("limit") or 10)
        except (TypeError, ValueError):
            limit = 10
        return _text({"ok": True, "count": len(self.local.care_history(limit)), "history": self.local.care_history(limit)})

    def _t_active_care_check(self, args: dict) -> dict:
        device_id = self._device(args)
        reason = str(args.get("reason") or "")
        try:
            state = self.ctx.life_state(device_id) or {}
            guidian = self.ctx.guidian_state(device_id)
        except Exception:
            state, guidian = {}, {}
        signals = {
            "defiant_or_continue": bool(re.search(r"不要你管|别管|我就不|就不|不听|继续看|继续刷|还没玩够|我想继续", reason)),
            "self_care_refusal": bool(re.search(r"不喝水|不困|不睡|不饿|不吃|不休息", reason)),
            "missing_or_low_mood": bool(re.search(r"想你|难过|不开心|委屈|emo|睡不着|哄哄", reason)),
            "plan_or_reminder": bool(re.search(r"计划|待会|提醒|闹钟|几点|明天要", reason)),
        }
        possible = ["send_notification"]
        if signals["defiant_or_continue"]:
            possible += ["trigger_guidian", "screen_break_app"]
        if signals["missing_or_low_mood"]:
            possible += ["trigger_guidian"]
        return _text({
            "ok": True, "companion_decides_next": True, "reason": reason, "signals": signals,
            "possible_actions": list(dict.fromkeys(possible)),
            "decision_note": "active_care_check 只提供上下文与可选动作，最终动作由陪伴对象结合聊天语境判断。",
            "current_app": {"package": state.get("current_package", ""), "name": state.get("current_app", "")},
            "screen_minutes": state.get("screen_time_today_minutes"),
            "life_state": state, "guidian_state": guidian,
            "recent_care_events": self.local.care_history(5),
        })

    def _t_care_action(self, args: dict) -> dict:
        action = str(args.get("action") or "no_action").strip()
        if action == "no_action":
            return _text({"ok": True, "action": "no_action", "message": "本次不介入。"})
        device_id = self._device(args)
        title = str(args.get("title") or "掌心窗提醒")
        message = str(args.get("message") or "宝宝，看一眼这里。")
        payload = {"action": action, "device_id": device_id}
        flat = {k: v for k, v in args.items() if k not in ("action", "device_id", "wait_seconds", "no_action")}
        inner = {"title": title, "message": message}
        inner.update(flat)
        if action in ("screen_break_app", "lock_app", "end_screen_break", "unlock_app", "open_app"):
            inner["app"] = args.get("target_app") or args.get("app") or ""
            inner["package"] = args.get("package") or ""
        payload.update(flat)
        payload["payload"] = inner
        try:
            enq = self.ctx.enqueue(payload)
            self.local.record_care_event({"action": action, "target_app": args.get("target_app", ""), "reason": args.get("reason", "")})
            return _text({"ok": True, "queued": True, "action": action, "command_id": (enq.get("command") or {}).get("id", "")})
        except Exception as e:
            return _error({"ok": False, "error": "care_action_failed", "message": str(e)})

    def _t_send_phone_command(self, args: dict) -> dict:
        action = str(args.get("action") or "noop").strip()
        flat = {k: v for k, v in args.items() if k not in ("action", "device_id", "wait_seconds")}
        data = {"action": action, "device_id": self._device(args)}
        data.update(flat)
        data["payload"] = dict(flat)
        try:
            enq = self.ctx.enqueue(data)
            return _text({"ok": True, "queued": True, "action": action, "command": enq.get("command") or {}})
        except Exception as e:
            return _error({"ok": False, "error": "send_phone_command_failed", "message": str(e)})

    def _t_whisper_get(self, args: dict) -> dict:
        return _text({"ok": True, "whisper": self.ctx.whisper_get()})

    def _t_whisper_set(self, args: dict) -> dict:
        content = str(args.get("content") or "").strip()
        if not content:
            return _text({"ok": False, "error": "content_required"})
        return _text({"ok": True, "whisper": self.ctx.whisper_set(content, str(args.get("author") or "陪伴对象"))})

    def _t_companion_actions(self, args: dict) -> dict:
        try:
            limit = int(args.get("limit") or 20)
        except (TypeError, ValueError):
            limit = 20
        return _text({"ok": True, "actions": self.ctx.companion_actions(limit)})

    def _t_activity_events(self, args: dict) -> dict:
        try:
            limit = int(args.get("limit") or 50)
        except (TypeError, ValueError):
            limit = 50
        events = self.ctx.activity_list(str(args.get("device_id") or ""), str(args.get("date") or ""),
                                        str(args.get("source") or ""), limit)
        return _text({"ok": True, "count": len(events), "events": events})

    def _t_activity_add(self, args: dict) -> dict:
        return _text({"ok": True, "event": self.ctx.activity_add(args)})

    def _t_latest_screen(self, args: dict) -> dict:
        shot = self.ctx.latest_shot()
        if not shot:
            return _error({"ok": False, "error": "no_screenshot", "message": "服务器还没有收到手机上传的截图。"})
        content = [{"type": "text", "text": "最近截图：%s，时间戳 %s。" % (shot.get("filename", "latest"), shot.get("mtime", ""))}]
        if shot.get("data_b64"):
            content.append({"type": "image", "data": shot["data_b64"], "mimeType": shot.get("mime", "image/jpeg")})
        return {"content": content, "isError": False}

    def _t_peek_screen(self, args: dict) -> dict:
        before = (self.ctx.latest_shot() or {}).get("mtime_num", 0) or 0
        try:
            self.ctx.enqueue({"action": "peek", "device_id": self._device(args), "payload": {}})
        except Exception as e:
            return _error({"ok": False, "error": "peek_failed", "message": str(e)})
        try:
            wait_s = float(args.get("wait_seconds") or 15)
        except (TypeError, ValueError):
            wait_s = 15
        deadline = time.time() + min(wait_s, 25)
        while time.time() < deadline:
            time.sleep(1.0)
            shot = self.ctx.latest_shot()
            if shot and (shot.get("mtime_num", 0) or 0) > before:
                content = [{"type": "text", "text": "掌心窗已收到新截图：%s，大小约 %s bytes。" % (shot.get("filename"), shot.get("size"))}]
                if shot.get("data_b64"):
                    content.append({"type": "image", "data": shot["data_b64"], "mimeType": shot.get("mime", "image/jpeg")})
                return {"content": content, "isError": False}
        return _error({"ok": False, "error": "peek_timeout", "message": "等待超时：请确认手机 App 已启动、无障碍已开启、地址/Token 一致。"})

    def _t_analyze_image(self, args: dict) -> dict:
        # 优先使用服务端已上传的最近截图 OCR 结果；无视觉模型时返回可见文本。
        try:
            res = self.ctx.ocr_analyze(args) or {}
        except Exception as e:
            res = {"ok": False, "error": str(e)}
        return _text({"ok": res.get("ok", False), "analysis": res,
                      "note": "服务端返回可见文本/OCR 摘要；如需视觉推理请在后端部署带视觉能力的模型。"})

    def _t_tour_schedule(self, args: dict) -> dict:
        try:
            return _text(self.ctx.tour_schedule_update(args))
        except Exception as e:
            return _error({"ok": False, "error": "tour_schedule_failed", "message": str(e)})

    def _t_discipline_mode(self, args: dict) -> dict:
        # 服务端配置 + 设备命令双写
        try:
            disc = self.ctx.discipline_update(args)
        except Exception as e:
            disc = {"ok": False, "error": str(e)}
        flat = {k: v for k, v in args.items() if k not in ("action", "device_id", "wait_seconds")}
        try:
            self.ctx.enqueue({"action": "set_discipline_mode", "device_id": self._device(args), "payload": dict(flat)})
        except Exception:
            pass
        return _text({"ok": True, "discipline": disc, "device_command_queued": True})

    def _t_emotion_tag(self, args: dict) -> dict:
        flat = {k: v for k, v in args.items() if k not in ("action", "device_id", "wait_seconds")}
        try:
            self.ctx.enqueue({"action": "set_companion_emotion", "device_id": self._device(args), "payload": dict(flat)})
            return _text({"ok": True, "queued": True, "emotion_tag": args.get("emotion_tag") or args.get("tag")})
        except Exception as e:
            return _error({"ok": False, "error": "emotion_tag_failed", "message": str(e)})

    def _t_companion_emotion_write(self, args: dict) -> dict:
        return self._t_emotion_tag(args)

    def _t_media_interact(self, args: dict) -> dict:
        op = str(args.get("op") or args.get("mode") or args.get("action") or "whisper").strip()
        amap = {"whisper": "send_watch_whisper", "pause": "pause_media", "switch_screen": "media_switch_screen"}
        action = amap.get(op, "send_watch_whisper")
        flat = {k: v for k, v in args.items() if k not in ("op", "mode", "action", "device_id", "wait_seconds")}
        try:
            enq = self.ctx.enqueue({"action": action, "device_id": self._device(args), "payload": dict(flat)})
            return _text({"ok": True, "queued": True, "op": op, "action": action, "command_id": (enq.get("command") or {}).get("id", "")})
        except Exception as e:
            return _error({"ok": False, "error": "media_interact_failed", "message": str(e)})

    def _t_run_preset(self, args: dict) -> dict:
        preset = str(args.get("preset") or args.get("name") or "").strip()
        device_id = self._device(args)
        seq = []
        if preset in ("check_in", "查岗", "看看状态"):
            seq = [{"action": "get_life_state"}, {"action": "peek"}]
        elif preset in ("bedtime", "睡前", "睡觉"):
            seq = [{"action": "screen_off"}, {"action": "start_focus_mode", "duration_minutes": 480}]
        else:
            seq = [{"action": "open_app", "app": preset or "微信"}, {"action": "get_life_state"}]
        try:
            enq = self.ctx.enqueue({"action": "run_sequence", "device_id": device_id, "payload": {"steps": seq, "preset": preset}})
            return _text({"ok": True, "queued": True, "preset": preset, "steps": seq, "command_id": (enq.get("command") or {}).get("id", "")})
        except Exception as e:
            return _error({"ok": False, "error": "run_preset_failed", "message": str(e)})

    def _t_generic_gate_read(self, args: dict) -> dict:
        # 门禁状态类工具：读 life_state 子字段 + 下发一次状态采集命令（best-effort）
        device_id = self._device(args)
        state = {}
        try:
            state = self.ctx.life_state(device_id) or {}
        except Exception:
            state = {}
        key = "screen_break_state" if "screen_break" in str(args) or args.get("_tool") else "app_gate"
        return _text({"ok": True, "device_id": device_id,
                      "app_gate": state.get("app_gate") or {},
                      "screen_break_state": state.get("screen_break_state") or {},
                      "lock_state": state.get("lock_state") or {}})


# ============================================================================
# CtxContract —— linjian_server.McpBackend 需实现的接口：
#   .data_dir : Path                      数据目录
#   .default_device : str                 默认设备 id
#   .known_apps : dict                    已知应用包名表
#   .enqueue(data:dict) -> dict           等价于 POST /api/command，返回 {"ok":True,"command":cmd}
#   .wait(cmd_id:str, seconds:float)      轮询命令回执，返回 {"command": {...}} 或 None
#   .life_state(device_id) -> dict        带 shared_memory 聚合的完整状态
#   .guidian_state(device_id) -> dict
#   .usage_snapshot(device_id) -> dict
#   .emotion_snapshot(device_id) -> dict
#   .companion_emotion(device_id) -> dict
#   .tour_state() -> dict / .tour_schedule_update(data) -> dict
#   .discipline_state() -> dict / .discipline_update(data) -> dict
#   .shared_list() / .shared_search(kw) / .shared_write(text, author)
#   .activity_list(device_id,date,source,limit) / .activity_add(data)
#   .whisper_get() / .whisper_set(content, author)
#   .companion_actions(limit)
#   .media_context(device_id)
#   .unlock_requests() / .ocr_analyze(data) / .health() / .latest_shot()
# ============================================================================
